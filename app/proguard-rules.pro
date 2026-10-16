# Readable stack traces in Play Console crash reports (upload mapping.txt with each release).
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# kXML2 is compiled against but provided by the Android framework. LandXML uses XmlPullParser.
-dontwarn org.kxml2.**
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.** { *; }

# Enum names and declaration order are stored in project files and preferences
# (theme, units, surface elevation paint, GNSS source ordinal, height-mode ordinal).
-keepclassmembers enum com.appdwg.app.** { *; }
-keepclassmembers enum com.appdwg.core.** { *; }

# Play Billing subscriptions gromatic_pro_mensal / gromatic_pro_anual. The product ids are
# string constants; the client and our store must survive R8 full mode (the AAR's consumer
# rules are applied as well).
-keep class com.android.billingclient.** { *; }
-keep class com.android.vending.billing.** { *; }
-keep class com.appdwg.app.ProStore { *; }
-keep class com.appdwg.app.ProPlan { *; }
-keep class com.appdwg.app.ProStore$Simulation { *; }
-keep class com.appdwg.app.ProFeature { *; }
-keep class com.appdwg.app.ProAccess { *; }
-keep class com.appdwg.app.ProEntitlement { *; }
-keep class com.appdwg.app.SubscriptionPurchase { *; }
-keep class com.appdwg.app.EntitlementSession { *; }

# Classpath catalogues (ipr736.json, i18n/en.tsv, idf/brasil.tsv) are opened by absolute
# path. R8 full mode repackages the loader classes; a relative getResourceAsStream then
# looks in the new package and the release build crashes on launch. Do not switch those
# loads back to a name relative to the class.

# File parsers and the binary project cache. They are called directly, but R8 full mode
# must not drop a format branch or the cache layout that reopens a project.
-keep class com.appdwg.core.LandXmlReader { *; }
-keep class com.appdwg.core.LandXmlWriter { *; }
-keep class com.appdwg.core.DxfReader { *; }
-keep class com.appdwg.core.DxfWriter { *; }
-keep class com.appdwg.core.AeccPointCache { *; }
-keep class com.appdwg.core.ProjectCache { *; }
-keep class com.appdwg.core.PointFile { *; }
-keep class com.appdwg.core.Gis { *; }
-keep class com.appdwg.core.KmlWriter { *; }
-keep class com.appdwg.core.TiffReader { *; }

# NTRIP caster client (sockets, started from GnssManager on its own thread).
-keep class com.appdwg.core.NtripClient { *; }
-keep class com.appdwg.core.NtripSettings { *; }
-keep class com.appdwg.app.GnssManager { *; }

# JNI: keep native methods and the classes that declare them (this app and its dependencies).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Kotlin serialization is not used; coroutines and Billing ship their own consumer rules.
# Do not warn on optional Play / XML types that are absent from the compile classpath.
-dontwarn com.android.billingclient.**
