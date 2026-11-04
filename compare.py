import torch
from transformers import MarianMTModel, MarianTokenizer

ZH = ["你好，你今天怎么样？", "免费送货，订单满50美元", "由于轨道维修，周六机场和市中心之间的火车每30分钟一班。",
      "请输入您的密码", "立即购买", "设置", "这款手机支持快速充电和无线充电。", "您的订单已发货，预计三天内送达。"]
AR = ["مرحبا، كيف حالك اليوم؟", "شحن مجاني للطلبات التي تزيد عن 50 دولارًا",
      "بسبب أعمال الصيانة، ستعمل القطارات بين المطار ووسط المدينة كل 30 دقيقة يوم السبت.",
      "اشتر تذكرة", "الإعدادات", "يجب أن تحتوي كلمة المرور على 8 أحرف على الأقل.", "تم التحديث قبل ساعتين",
      "تم شحن طلبك ومن المتوقع أن يصل خلال ثلاثة أيام."]
cache = {}
def tr(name, texts):
    if name not in cache:
        cache[name] = (MarianTokenizer.from_pretrained(name), MarianMTModel.from_pretrained(name).eval())
    tok, m = cache[name]
    out = []
    with torch.no_grad():
        for t in texts:
            g = m.generate(**tok(t, return_tensors="pt"), num_beams=1, do_sample=False, max_new_tokens=128)
            out.append(tok.decode(g[0], skip_special_tokens=True))
    return out
for lang, data in [("zh", ZH), ("ar", AR)]:
    direct = tr(f"Helsinki-NLP/opus-mt-{lang}-he", data)
    mid = tr(f"Helsinki-NLP/opus-mt-{lang}-en", data)
    pivot = tr("Helsinki-NLP/opus-mt-en-he", mid)
    for s, d, e, p in zip(data, direct, mid, pivot):
        print(f"SRC|{s}\nDIR|{d}\nENG|{e}\nPIV|{p}\n")
