# libGDX reaches into its backends and natives reflectively, so those entry
# points have to survive shrinking.
-keep class com.badlogic.gdx.backends.android.** { *; }
-keep class com.badlogic.gdx.utils.** { *; }
-keepclassmembers class com.badlogic.gdx.** { <init>(...); }
-dontwarn com.badlogic.gdx.**
