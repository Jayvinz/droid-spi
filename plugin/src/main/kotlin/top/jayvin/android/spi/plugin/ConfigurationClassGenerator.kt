package top.jayvin.android.spi.plugin

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Generates the provider configuration bytecode. Three points must hold:
 *
 * 1. **No `<clinit>`**. `ServiceLoader.initialize` directly `new`s the configuration class;
 *    provider class literals appear only inside method bodies. While a dynamic module is not
 *    installed, only the corresponding method throws `NoClassDefFoundError`; the configuration
 *    class itself is never marked erroneous by the JVM and can be re-probed indefinitely.
 * 2. `services()` references only **interfaces**, inside the method body; interfaces must live
 *    in base and are always resolvable.
 * 3. `@ProviderFactory` generates `INVOKESTATIC`, never `Method.invoke`.
 */
internal object ConfigurationClassGenerator {

    private const val SUPER_NAME = "java/lang/Object"
    private const val CONFIGURATION = "top/jayvin/android/spi/ProviderConfiguration"

    fun generate(pkg: String, simpleName: String, rawEntries: List<ProviderEntry>): ByteArray {
        val entries = rawEntries.sortedWith(compareBy({ it.service }, { it.key }))
        val owner = if (pkg.isEmpty()) simpleName else "${pkg.toInternalName()}/$simpleName"
        val grouped = entries.groupBy { it.service }

        val cw = object : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
            // Implementations may come from uninstalled dynamic modules; they must not be loaded here.
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
        emitServices(cw, grouped.keys.toList())
        emitKeys(cw, entries.map { it.key })
        emitProviderOf(cw, grouped)
        emitNewProvider(cw, grouped)
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

    private fun emitServices(cw: ClassWriter, services: List<String>) =
        emitConstantArray(
            cw, "services", "java/lang/Class",
            services.map { Type.getObjectType(it.toInternalName()) },
        )

    private fun emitKeys(cw: ClassWriter, keys: List<String>) =
        emitConstantArray(cw, "keys", "java/lang/String", keys)

    /** The fixed `{ANEWARRAY; DUP; index; ldc; AASTORE}` pattern shared by services() and keys(). */
    private fun emitConstantArray(
        cw: ClassWriter,
        methodName: String,
        componentType: String,
        values: List<Any>,
    ) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()[L$componentType;", null, null)
        mv.visitCode()
        mv.visitLdcInsn(values.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, componentType)
        values.forEachIndexed { i, value ->
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(i)
            mv.visitLdcInsn(value)
            mv.visitInsn(Opcodes.AASTORE)
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitProviderOf(cw: ClassWriter, grouped: Map<String, List<ProviderEntry>>) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "providerOf",
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Class;",
            null,
            null,
        )
        mv.visitCode()
        val end = Label()
        emitServiceKeyChain(mv, grouped, end) { entry ->
            mv.visitLdcInsn(Type.getObjectType(entry.provider.toInternalName()))
            mv.visitInsn(Opcodes.ARETURN)
        }
        mv.visitLabel(end)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun emitNewProvider(cw: ClassWriter, grouped: Map<String, List<ProviderEntry>>) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "newProvider",
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;",
            null,
            null,
        )
        mv.visitCode()
        val end = Label()
        emitServiceKeyChain(mv, grouped, end) { entry ->
            val impl = entry.provider.toInternalName()
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
     * Generates a two-level dispatch on `arg0 == service && arg1 == key`. [onHit] runs on a match;
     * when no branch matches, control falls through to the [end] label (which returns null).
     */
    private fun emitServiceKeyChain(
        mv: MethodVisitor,
        grouped: Map<String, List<ProviderEntry>>,
        end: Label,
        onHit: (ProviderEntry) -> Unit,
    ) {
        grouped.forEach { (service, group) ->
            val nextService = Label()
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitLdcInsn(Type.getObjectType(service.toInternalName()))
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
}
