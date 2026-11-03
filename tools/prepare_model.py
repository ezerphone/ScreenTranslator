"""Prepare the bundled English -> Hebrew translation model.

Downloads Helsinki-NLP/opus-mt-en-he, exports it to ONNX, quantizes it to int8 and writes
everything the app needs into the assets folder. Also writes a reference file (token ids and
full-precision translations) that the JVM test compares against.

Usage:
    python tools/prepare_model.py <assets_dir> <work_dir> <reference_tsv>
"""

import shutil
import sys
from pathlib import Path

import sentencepiece as spm
import torch
from huggingface_hub import hf_hub_download
from onnxruntime.quantization import QuantType, quantize_dynamic
from optimum.exporters.onnx import main_export
from transformers import MarianMTModel, MarianTokenizer

MODEL = "Helsinki-NLP/opus-mt-en-he"

TEST_SENTENCES = [
    "Hello, how are you today?",
    "Weekend train schedule changes",
    "Due to track maintenance, trains between the airport and the city center will run every 30 minutes on Saturday.",
    "Updated 2 hours ago",
    "Buy ticket",
    "Settings",
    "Your password must contain at least 8 characters.",
    "Free shipping on orders over $50",
]


def main() -> None:
    assets = Path(sys.argv[1])
    work = Path(sys.argv[2])
    reference = Path(sys.argv[3])
    onnx_dir = work / "onnx"

    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(parents=True)
    work.mkdir(parents=True, exist_ok=True)

    # 1. Export to ONNX (encoder, decoder, decoder with key/value cache).
    main_export(MODEL, output=onnx_dir, task="text2text-generation-with-past", no_post_process=True)
    print("exported:", sorted(p.name for p in onnx_dir.iterdir()))

    # 2. Quantize weights to int8 (about 4x smaller).
    for src, dst in [
        ("encoder_model.onnx", "encoder.onnx"),
        ("decoder_model.onnx", "decoder.onnx"),
        ("decoder_with_past_model.onnx", "decoder_past.onnx"),
    ]:
        # Per-channel scales keep short phrases ("Buy ticket") accurate after quantization.
        # Unsigned weights (U8U8) avoid the int8 overflow some x86 CPUs hit with signed weights,
        # so results are the same on every processor.
        quantize_dynamic(
            str(onnx_dir / src),
            str(assets / dst),
            weight_type=QuantType.QUInt8,
            per_channel=True,
        )
        print(f"{dst}: {(assets / dst).stat().st_size / 1e6:.1f} MB")

    # 3. Tokenizer data as plain text.
    source_spm = hf_hub_download(MODEL, "source.spm")
    sp = spm.SentencePieceProcessor(model_file=source_spm)
    with open(assets / "source.tsv", "w", encoding="utf-8") as f:
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

    tokenizer = MarianTokenizer.from_pretrained(MODEL)
    with open(assets / "vocab.tsv", "w", encoding="utf-8") as f:
        for token, idx in tokenizer.encoder.items():
            if "\t" in token or "\n" in token:
                continue
            f.write(f"{token}\t{idx}\n")

    model = MarianMTModel.from_pretrained(MODEL)
    model.eval()
    with open(assets / "config.properties", "w", encoding="utf-8") as f:
        f.write(f"pad_id={tokenizer.pad_token_id}\n")
        f.write(f"eos_id={tokenizer.eos_token_id}\n")
        f.write(f"unk_id={tokenizer.unk_token_id}\n")
        f.write(f"decoder_start_id={model.config.decoder_start_token_id}\n")
        f.write("max_length=512\n")
    print((assets / "config.properties").read_text())

    # 4. Reference: token ids + greedy full-precision translations.
    reference.parent.mkdir(parents=True, exist_ok=True)
    with open(reference, "w", encoding="utf-8") as f, torch.no_grad():
        for sentence in TEST_SENTENCES:
            batch = tokenizer(sentence, return_tensors="pt")
            ids = " ".join(str(i) for i in batch["input_ids"][0].tolist())
            generated = model.generate(**batch, num_beams=1, do_sample=False, max_new_tokens=128)
            translation = tokenizer.decode(generated[0], skip_special_tokens=True)
            f.write(f"{sentence}\t{ids}\t{translation}\n")
            print(sentence, "->", translation)


if __name__ == "__main__":
    main()
