import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class PatchAarNoopMethod {
    private PatchAarNoopMethod() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: <aar> <class/internal/Name> <methodName> <methodDesc>");
        }
        File aar = new File(args[0]);
        File temp = File.createTempFile("patched-aar", ".aar", aar.getParentFile());
        byte[] patchedClasses = patchClassesJar(readClassesJar(aar), args[1], args[2], args[3]);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(aar.toPath()));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp.toPath()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ZipEntry next = new ZipEntry(entry.getName());
                out.putNextEntry(next);
                if ("classes.jar".equals(entry.getName())) {
                    out.write(patchedClasses);
                } else {
                    copy(in, out);
                }
                out.closeEntry();
            }
        }
        Files.move(temp.toPath(), aar.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] readClassesJar(File aar) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(aar.toPath()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if ("classes.jar".equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    copy(in, out);
                    return out.toByteArray();
                }
            }
        }
        throw new IllegalStateException("classes.jar not found in " + aar);
    }

    private static byte[] patchClassesJar(byte[] classesJar, String className, String methodName, String methodDesc) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean[] patched = new boolean[1];
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(classesJar));
             JarOutputStream out = new JarOutputStream(output)) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                JarEntry next = new JarEntry(entry.getName());
                out.putNextEntry(next);
                if ((className + ".class").equals(entry.getName())) {
                    out.write(patchClass(readAll(in), methodName, methodDesc, patched));
                } else {
                    copy(in, out);
                }
                out.closeEntry();
            }
        }
        if (!patched[0]) {
            throw new IllegalStateException("method not patched: " + className + "#" + methodName + methodDesc);
        }
        return output.toByteArray();
    }

    private static byte[] patchClass(byte[] input, String methodName, String methodDesc, boolean[] patched) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals(methodName) && descriptor.equals(methodDesc)) {
                    patched[0] = true;
                    visitor.visitCode();
                    emitDefaultReturn(visitor, descriptor);
                    visitor.visitMaxs(0, 0);
                    visitor.visitEnd();
                    return null;
                }
                return visitor;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void emitDefaultReturn(MethodVisitor visitor, String descriptor) {
        char returnType = descriptor.charAt(descriptor.lastIndexOf(')') + 1);
        switch (returnType) {
            case 'V':
                visitor.visitInsn(Opcodes.RETURN);
                break;
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
                visitor.visitInsn(Opcodes.ICONST_0);
                visitor.visitInsn(Opcodes.IRETURN);
                break;
            case 'J':
                visitor.visitInsn(Opcodes.LCONST_0);
                visitor.visitInsn(Opcodes.LRETURN);
                break;
            case 'F':
                visitor.visitInsn(Opcodes.FCONST_0);
                visitor.visitInsn(Opcodes.FRETURN);
                break;
            case 'D':
                visitor.visitInsn(Opcodes.DCONST_0);
                visitor.visitInsn(Opcodes.DRETURN);
                break;
            default:
                visitor.visitInsn(Opcodes.ACONST_NULL);
                visitor.visitInsn(Opcodes.ARETURN);
                break;
        }
    }

    private static byte[] readAll(JarInputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }
}
