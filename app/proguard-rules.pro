# demo 的 release 未开启 minify（见 app/build.gradle.kts），本文件仅作接入方可直接复用的示例。
# 动态模块的实现类由生成的注册表以 `ldc` 常量引用，R8 能看到引用会自动保留。
# 但 base 的 R8 会把这些"尚未安装的模块类"报成 missing class，按需要放行：
-dontwarn com.example.feature.camera.**
