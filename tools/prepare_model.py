"""Prepare the bundled translation models and OCR data.

For every model in MODELS: download from Hugging Face, export to ONNX (encoder + merged decoder
with key/value cache), quantize to 8-bit and write what the app needs to
<assets_dir>/translator/<name>/. Chinese and Arabic are translated through English
(zh->en->he, ar->en->he), which tested clearly better than the direct zh->he / ar->he models.

Also downloads the Tesseract Arabic data to <assets_dir>/tessdata/ and writes, per model,
a reference file (token ids + full-precision translations) for the JVM test.

Usage:
    python tools/prepare_model.py <assets_dir> <work_dir> <reference_dir>
"""

import shutil
import sys
import urllib.request
from pathlib import Path

import sentencepiece as spm
import torch
from huggingface_hub import hf_hub_download
from onnxruntime.quantization import QuantType, quantize_dynamic
from optimum.exporters.onnx import main_export
from transformers import MarianMTModel, MarianTokenizer

MODELS = {
    "en-he": ("Helsinki-NLP/opus-mt-en-he", "he"),
    "zh-en": ("Helsinki-NLP/opus-mt-zh-en", "en"),
    "ar-en": ("Helsinki-NLP/opus-mt-ar-en", "en"),
}

TEST_SENTENCES = {
    "en-he": [
        "Hello, how are you today?",
        "Weekend train schedule changes",
        "Due to track maintenance, trains between the airport and the city center will run every 30 minutes on Saturday.",
        "Updated 2 hours ago",
        "Buy ticket",
        "Settings",
        "Your password must contain at least 8 characters.",
        "Free shipping on orders over $50",
    ],
    "zh-en": [
        "你好，你今天怎么样？",
        "免费送货，订单满50美元",
        "由于轨道维修，周六机场和市中心之间的火车每30分钟一班。",
        "请输入您的密码",
        "立即购买",
        "设置",
        "这款手机支持快速充电和无线充电。",
        "您的订单已发货，预计三天内送达。",
    ],
    "ar-en": [
        "مرحبا، كيف حالك اليوم؟",
        "شحن مجاني للطلبات التي تزيد عن 50 دولارًا",
        "بسبب أعمال الصيانة، ستعمل القطارات بين المطار ووسط المدينة كل 30 دقيقة يوم السبت.",
        "اشتر تذكرة",
        "الإعدادات",
        "يجب أن تحتوي كلمة المرور على 8 أحرف على الأقل.",
        "تم التحديث قبل ساعتين",
        "تم شحن طلبك ومن المتوقع أن يصل خلال ثلاثة أيام.",
    ],
}

TESSDATA = {
    "ara.traineddata": "https://github.com/tesseract-ocr/tessdata/raw/main/ara.traineddata",
}


def write_vocab(path: Path, mapping: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        for token, idx in mapping.items():
            if "\t" in token or "\n" in token:
                continue
            f.write(f"{token}\t{idx}\n")


def prepare_model(name: str, repo: str, target: str, out: Path, work: Path, reference: Path) -> None:
    onnx_dir = work / name
    out.mkdir(parents=True)

    # 1. Export: encoder + one merged decoder (first step and cached steps in one file).
    main_export(repo, output=onnx_dir, task="text2text-generation-with-past")
    print(name, "exported:", sorted(p.name for p in onnx_dir.iterdir()))

    # 2. 8-bit weights. Unsigned + per-channel works the same on every CPU and keeps quality.
    for src, dst in [("encoder_model.onnx", "encoder.onnx"), ("decoder_model_merged.onnx", "decoder.onnx")]:
        quantize_dynamic(
            str(onnx_dir / src),
            str(out / dst),
            weight_type=QuantType.QUInt8,
            per_channel=True,
            extra_options={"EnableSubgraph": True},
        )
        print(f"{name}/{dst}: {(out / dst).stat().st_size / 1e6:.1f} MB")

    # 3. Tokenizer data as plain text.
    sp = spm.SentencePieceProcessor(model_file=hf_hub_download(repo, "source.spm"))
    with open(out / "source.tsv", "w", encoding="utf-8") as f:
        for i in range(sp.get_piece_size()):
            if sp.is_unknown(i):
                kind = 2
            elif sp.is_control(i):
                kind = 3
            elif sp.is_unused(i):
                kind = 5
            elif sp.is_byte(i):
                kind = 6
            else:
                kind = 1
            f.write(f"{sp.id_to_piece(i)}\t{sp.get_score(i)!r}\t{kind}\n")

    tokenizer = MarianTokenizer.from_pretrained(repo)
    write_vocab(out / "vocab.tsv", tokenizer.encoder)
    write_vocab(out / "target_vocab.tsv", getattr(tokenizer, "target_encoder", None) or tokenizer.encoder)

    model = MarianMTModel.from_pretrained(repo)
    model.eval()
    cfg = model.config
    with open(out / "config.properties", "w", encoding="utf-8") as f:
        f.write(f"pad_id={tokenizer.pad_token_id}\n")
        f.write(f"eos_id={tokenizer.eos_token_id}\n")
        f.write(f"unk_id={tokenizer.unk_token_id}\n")
        f.write(f"decoder_start_id={cfg.decoder_start_token_id}\n")
        f.write(f"decoder_layers={cfg.decoder_layers}\n")
        f.write(f"decoder_heads={cfg.decoder_attention_heads}\n")
        f.write(f"d_model={cfg.d_model}\n")
        f.write(f"target={target}\n")
        f.write("max_length=512\n")
    print((out / "config.properties").read_text())

    # 4. Reference: token ids + greedy full-precision translations.
    with open(reference / f"{name}.tsv", "w", encoding="utf-8") as f, torch.no_grad():
        for sentence in TEST_SENTENCES[name]:
            batch = tokenizer(sentence, return_tensors="pt")
            ids = " ".join(str(i) for i in batch["input_ids"][0].tolist())
            generated = model.generate(**batch, num_beams=1, do_sample=False, max_new_tokens=128)
            translation = tokenizer.decode(generated[0], skip_special_tokens=True)
            f.write(f"{sentence}\t{ids}\t{translation}\n")
            print(sentence, "->", translation)

    shutil.rmtree(onnx_dir)


def main() -> None:
    assets = Path(sys.argv[1])
    work = Path(sys.argv[2])
    reference = Path(sys.argv[3])

    for sub in ["translator", "tessdata"]:
        if (assets / sub).exists():
            shutil.rmtree(assets / sub)
    work.mkdir(parents=True, exist_ok=True)
    reference.mkdir(parents=True, exist_ok=True)

    for name, (repo, target) in MODELS.items():
        prepare_model(name, repo, target, assets / "translator" / name, work, reference)

    (assets / "tessdata").mkdir(parents=True)
    for file, url in TESSDATA.items():
        urllib.request.urlretrieve(url, assets / "tessdata" / file)
        print(f"tessdata/{file}: {(assets / 'tessdata' / file).stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
