# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Drawables inflated from XML by class name (pill_bg, glass_panel): R8 cannot see that use.
-keep class com.gathin.tvhome.LiquidGlassDrawable { public <init>(); }
-keep class com.gathin.tvhome.LiquidGlassDrawable$Panel { public <init>(); }
