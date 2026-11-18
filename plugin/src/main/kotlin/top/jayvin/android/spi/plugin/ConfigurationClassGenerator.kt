package top.jayvin.android.spi.plugin

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * 生成 provider configuration 字节码。三个必须守住的点：
 *
 * 1. **不生成 `<clinit>`**。配置类由 `ServiceLoader.initialize` 直接 `new`；provider 类字面量
 *    只出现在方法体里，动态模块未装时只有对应方法抛 `NoClassDefFoundError`，配置类自身不会被
 *    JVM 标记为 erroneous，装好后可无限次重探。
 * 2. `services()` 只在方法体里引用**接口**，接口必须在 base，永远可解析。
 * 3. 走 `@ProviderFactory` 时生成 `INVOKESTATIC`，无 `Method.invoke`。
 */
internal object ConfigurationClassGenerator {

    private const val SUPER_NAME = "java/lang/Object"
    private const val CONFIGURATION = "top/jayvin/android/spi/ProviderConfiguration"

    fun generate(pkg: String, simpleName: String, rawEntries: List<ProviderEntry>): ByteArray {
        val entries = rawEntries.sortedWith(compareBy({ it.service }, { it.key }))
        val owner = if (pkg.isEmpty()) simpleName else pkg.replace('.', '/') + "/" + simpleName

        val cw = object : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
            // 实现类可能来自尚未安装的动态模块，不能真的去加载它们
            override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
        }

        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            owner,
            null,
            SUPER_NAME,
            arrayOf(CONFIGURATION),
        )

        emitConstructor(cw)
        emitServices(cw, entries)
        emitKeys(cw, entries)
        emitProviderOf(cw, entries)
        emitNewProvider(cw, entries)
        cw.visitEnd()

        return cw.toByteArray()
    }

    private fun emitConstructor(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SUPER_NAME, "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitServices(cw: ClassWriter, entries: List<ProviderEntry>) {
        val services = entries.map { it.service }.distinct()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "services", "()[Ljava/lang/Class;", null, null)
        mv.visitCode()
        pushInt(mv, services.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class")
        services.forEachIndexed { i, service ->
            mv.visitInsn(Opcodes.DUP)
            pushInt(mv, i)
            mv.visitLdcInsn(Type.getObjectType(service.replace('.', '/')))
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitKeys(cw: ClassWriter, entries: List<ProviderEntry>) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "keys", "()[Ljava/lang/String;", null, null)
        mv.visitCode()
        pushInt(mv, entries.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
        entries.forEachIndexed { i, entry ->
            mv.visitInsn(Opcodes.DUP)
            pushInt(mv, i)
            mv.visitLdcInsn(entry.key)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitProviderOf(cw: ClassWriter, entries: List<ProviderEntry>) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "providerOf",
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Class;",
            null,
            null,
        )
        mv.visitCode()
        val end = Label()
        emitServiceKeyChain(mv, entries, end) { entry ->
            mv.visitLdcInsn(Type.getObjectType(entry.provider.replace('.', '/')))
            mv.visitInsn(Opcodes.ARETURN)
        }
        mv.visitLabel(end)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitNewProvider(cw: ClassWriter, entries: List<ProviderEntry>) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "newProvider",
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;",
            null,
            null,
        )
        mv.visitCode()
        val end = Label()
        emitServiceKeyChain(mv, entries, end) { entry ->
            val impl = entry.provider.replace('.', '/')
            if (entry.factory.isEmpty()) {
                mv.visitTypeInsn(Opcodes.NEW, impl)
                mv.visitInsn(Opcodes.DUP)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, impl, "<init>", "()V", false)
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, impl, entry.factory, "()L$impl;", false)
            }
            mv.visitInsn(Opcodes.ARETURN)
        }
        mv.visitLabel(end)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * 生成 `arg0 == service && arg1 == key` 的两层分发。命中时调用 [onHit]，
     * 未命中任何分支则落到 [end] 标签（那里统一返回 null）。
     */
    private fun emitServiceKeyChain(
        mv: MethodVisitor,
        entries: List<ProviderEntry>,
        end: Label,
        onHit: (ProviderEntry) -> Unit,
    ) {
        entries.groupBy { it.service }.forEach { (service, group) ->
            val nextService = Label()
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitLdcInsn(Type.getObjectType(service.replace('.', '/')))
            mv.visitJumpInsn(Opcodes.IF_ACMPNE, nextService)

            group.forEach { entry ->
                val nextKey = Label()
                mv.visitVarInsn(Opcodes.ALOAD, 2)
                mv.visitLdcInsn(entry.key)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false,
                )
                mv.visitJumpInsn(Opcodes.IFEQ, nextKey)
                onHit(entry)
                mv.visitLabel(nextKey)
            }

            mv.visitJumpInsn(Opcodes.GOTO, end)
            mv.visitLabel(nextService)
        }
    }

    private fun pushInt(mv: MethodVisitor, value: Int) {
        when {
            value <= 5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
            value <= Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            value <= Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }
}
