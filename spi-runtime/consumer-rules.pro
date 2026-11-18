# 生成的注册表引导类被 AndroidManifest 的 <provider> 按名实例化，必须保留类名与构造器
-keep class top.fishquant.mobile.spi.generated.** { *; }

# 注册表里引用的动态模块实现类，R8 通过 ldc 常量能看到引用，无需额外规则；
# 但 base 模块的 R8 会把"尚未安装的动态模块类"报为 missing class，
# 需要在接入方按自己的模块名补 -dontwarn，例如：
#   -dontwarn com.example.feature.camera.**
