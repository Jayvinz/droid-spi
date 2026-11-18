# runtime 直接 new 生成的 ServiceConfiguration；app 未 apply 插件时允许该类缺失。
# R8 能看到 new 引用，存在时自动保留，不需要 -keep。
-dontwarn top.jayvin.android.spi.generated.ServiceConfiguration

# 注册表里引用的动态模块实现类，R8 通过 ldc 常量能看到引用，无需额外规则；
# 但 base 模块的 R8 会把"尚未安装的动态模块类"报为 missing class，
# 需要在接入方按自己的模块名补 -dontwarn，例如：
#   -dontwarn com.example.feature.camera.**
