# Protocol messages are (de)serialized reflectively by kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.coralcoder.iwatchbridge.**$$serializer { *; }
-keepclassmembers class com.coralcoder.iwatchbridge.** { *** Companion; }
-dontwarn org.bouncycastle.**
# Started by name through app_process as root.
-keep class com.coralcoder.iwatchbridge.root.RootHciHelper { public static void main(java.lang.String[]); }
