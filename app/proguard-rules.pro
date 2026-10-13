# Readable stack traces in Play Console crash reports (upload the mapping file with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kXML2 is compiled against but provided by the Android framework.
-dontwarn org.kxml2.**
-dontwarn org.xmlpull.v1.**
