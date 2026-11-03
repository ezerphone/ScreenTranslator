# מתרגם מסך (Android)

כפתור "תרגם" צף מעל כל אפליקציה: הקשה עליו מתרגמת לעברית את כל הטקסט באנגלית שעל המסך,
ומציגה את התרגום במקום המקורי. הכול רץ על הטלפון – בלי אינטרנט ובלי מפתח API.

## איך זה בנוי
- זיהוי טקסט: Google ML Kit (מודל לטיני מובנה).
- תרגום: המודל הפתוח [Helsinki-NLP/opus-mt-en-he](https://huggingface.co/Helsinki-NLP/opus-mt-en-he),
  מומר ל-ONNX ומכווץ ל-int8, ורץ עם ONNX Runtime. המודל ארוז בתוך ה-APK.
- `translator/` – מודול JVM טהור (טוקנייזר SentencePiece + פענוח עם מטמון), נבדק מול הפלט של transformers.
- `tools/prepare_model.py` – מוריד את המודל, ממיר, מכווץ וכותב ל-`app/src/main/assets/translator/`.

## בנייה
הבנייה רצה אוטומטית ב-GitHub Actions בכל push: הכנת המודל → בדיקת המתרגם → APK.
הקובץ מתפרסם ב-Releases בשם `ScreenTranslator.apk`.

לבנייה מקומית ב-Android Studio הרץ קודם (Python 3.11):
```
pip install --index-url https://download.pytorch.org/whl/cpu torch==2.5.1
pip install transformers==4.46.3 optimum==1.23.3 onnx==1.17.0 onnxruntime==1.20.1 sentencepiece==0.2.0 "huggingface_hub<0.27" "numpy<2.1"
python tools/prepare_model.py app/src/main/assets/translator build-model/work build-model/reference.tsv
```
