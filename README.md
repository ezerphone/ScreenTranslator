# תרגום (Android)

מתרגם לעברית טקסט באנגלית, בסינית ובערבית: על המסך (כפתור צף), במצלמה, בתמונות, בטקסט שכותבים
ובטקסט מסומן או משותף. הכול רץ על הטלפון – בלי אינטרנט ובלי הורדות.

## איך זה בנוי
- **שפת מקור אחת**: בוחרים במסך הבית אנגלית, סינית או ערבית, והבחירה קובעת בכל המקומות.
  (טקסט שהכתב שלו שונה בבירור – למשל כפתור באנגלית באפליקציה סינית – מתורגם מהשפה של הכתב.)
- זיהוי טקסט: Google ML Kit (מודל סיני, קורא גם אנגלית) ו-Tesseract לערבית.
- תרגום: Google ML Kit. המודלים (סינית, ערבית, עברית; אנגלית מובנית) ארוזים בתוך ה-APK.
- עיצוב בסגנון Google Translate, בהיר או כהה לפי הגדרת המערכת.

### חבילת השפות
Google לא מאפשרת לארוז את שפות ML Kit בתוך אפליקציה, לכן `language-pack/` מכיל עותק של התיקייה
הפרטית של האפליקציה אחרי שהשפות הורדו בה פעם אחת, מחולק לחלקים מתחת ל-100MB.
הבנייה מחברת אותו ל-`app/src/main/assets/languages.zip`, ו-`LanguagePack.kt` מחזיר אותו למקום
בהפעלה הראשונה ומפעיל את האפליקציה מחדש.

## בנייה
הבנייה רצה אוטומטית ב-GitHub Actions בכל push, והקובץ מתפרסם ב-Releases בשם `Translate.apk`.

לבנייה מקומית ב-Android Studio:
```
mkdir -p app/src/main/assets/tessdata
curl -L -o app/src/main/assets/tessdata/ara.traineddata https://github.com/tesseract-ocr/tessdata/raw/main/ara.traineddata
cat language-pack/languages.zip.part-* > app/src/main/assets/languages.zip
```
