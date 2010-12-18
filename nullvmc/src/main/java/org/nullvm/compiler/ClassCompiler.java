/*
 * Copyright (C) 2009 Niklas Therning <niklas(a)therning.org>
 * This file is part of JTouch.
 *
 * JTouch is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JTouch is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JTouch.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.nullvm.compiler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.nullvm.compiler.clazz.Clazz;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 *
 * @version $Id$
 */
public class ClassCompiler {
    
    public enum VerifyWhen {
        SKIP, NOW, DEFER
    }
    
    private ClassNode classNode;
    private Set<String> strings = new HashSet<String>();
    private PrintWriter out;
    private VerifyWhen verifyWhen = VerifyWhen.SKIP;
    
    public ClassCompiler setVerifyWhen(VerifyWhen verifyWhen) {
        this.verifyWhen = verifyWhen;
        return this;
    }
    
    public void compile(Clazz clazz, OutputStream out) throws IOException {
        this.out = new PrintWriter(new OutputStreamWriter(out, "UTF-8"));
        try {
            if (verifyWhen != VerifyWhen.SKIP) {
                clazz.verify();
            }
            compile(new ClassReader(clazz.getBytes()));
        } catch (VerifyError ve) {
            if (verifyWhen == VerifyWhen.DEFER) {
                compileWithVerifyError(clazz, ve);
            } else {
                throw ve;
            }
        }
    }
    
    private void compile(ClassReader cr) throws IOException {
        ClassNode cn = new ClassNode() {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
            }
        };
        cr.accept(cn, 0);
        compile(cn);
    }
    
    private void compileWithVerifyError(Clazz clazz, VerifyError ve) {
        String message = ve.getMessage() == null ? "" : ve.getMessage();
        this.strings = new HashSet<String>();
        writeHeader();
        out.println("; Strings");
        writeStringDefinition(out, message);
        out.format("define %%Class* @\"NullVM_%s\"(%%Env* %%env) {\n", LlvmUtil.mangleString(clazz.getClassName().replace('.', '/')));
        out.format("    invoke void @_nvmBcThrowVerifyError(%%Env* %%env, i8* %s) to label %%Success unwind label %%CatchAll\n", LlvmUtil.getStringReference(message));
        out.format("Success:\n");
        out.format("    ret %%Class* null\n");
        out.format("CatchAll:\n");
        out.format("    %%ehptr = call i8* @llvm.eh.exception()\n");
        out.format("    %%sel = call i64 (i8*, i8*, ...)* @llvm.eh.selector.i64(i8* %%ehptr, "
                + "i8* bitcast (i32 (i32, i32, i64, i8*, i8*)* @_nvmPersonality to i8*), i32 1)\n");
        out.format("    ret %%Class* null\n");
        out.println("}\n");
        out.flush();
    }
    
    @SuppressWarnings("unchecked")
    private void compile(ClassNode cn) {
        this.strings = new HashSet<String>();
        this.classNode = cn;
        
        writeHeader();
        
        List<FieldNode> classFields = new ArrayList<FieldNode>();
        List<FieldNode> instanceFields = new ArrayList<FieldNode>();

        for (FieldNode fieldNode : (List<FieldNode>) classNode.fields) {
            if ((fieldNode.access & Opcodes.ACC_STATIC) != 0) {
                classFields.add(fieldNode);
            } else {
                instanceFields.add(fieldNode);
            }
        }
        
        if (!classFields.isEmpty()) {
            List<String> names = new ArrayList<String>();
            List<String> types = new ArrayList<String>();
            for (FieldNode fieldNode : classFields) {
                names.add(Type.getType(fieldNode.desc).getClassName() + " " + fieldNode.name);
                types.add(LlvmUtil.javaTypeToLlvmType(Type.getType(fieldNode.desc)));
            }
            out.format("; {%s}\n", LlvmUtil.join(names));
            out.format("%%ClassFields = type {%s}\n", LlvmUtil.join(types));
        }
        
        if (!instanceFields.isEmpty()) {
            List<String> names = new ArrayList<String>();
            List<String> types = new ArrayList<String>();
            for (FieldNode fieldNode : instanceFields) {
                names.add(Type.getType(fieldNode.desc).getClassName() + " " + fieldNode.name);
                types.add(LlvmUtil.javaTypeToLlvmType(Type.getType(fieldNode.desc)));
            }
            out.format("; {%s}\n", LlvmUtil.join(names));
            out.format("%%InstanceFields = type {%s}\n", LlvmUtil.join(types));
        }
        
        out.println();
        
        out.println("; Strings");
        writeStringDefinition(out, classNode.name);
        if (classNode.superName != null) {
            writeStringDefinition(out, classNode.superName);
            writeStringDefinition(out, LlvmUtil.mangleString(classNode.superName));
        }
        for (int i = 0; i < classNode.interfaces.size(); i++) {
            writeStringDefinition(out, (String) classNode.interfaces.get(i));
            writeStringDefinition(out, LlvmUtil.mangleString((String) classNode.interfaces.get(i)));
        }
        for (FieldNode fieldNode : classFields) {
            writeStringDefinition(out, fieldNode.name);
            writeStringDefinition(out, fieldNode.desc);
        }
        for (FieldNode fieldNode : instanceFields) {
            writeStringDefinition(out, fieldNode.name);
            writeStringDefinition(out, fieldNode.desc);
        }
        for (MethodNode node : (List<MethodNode>) classNode.methods) {
            writeStringDefinition(out, node.name);
            writeStringDefinition(out, node.desc);
            if (!LlvmUtil.isNative(node)) {
                for (AbstractInsnNode insnNode : node.instructions.toArray()) {
                    if (insnNode instanceof MethodInsnNode) {
                        MethodInsnNode n = (MethodInsnNode) insnNode;
                        writeStringDefinition(out, n.owner);
                        writeStringDefinition(out, LlvmUtil.mangleString(n.owner));
                        writeStringDefinition(out, n.name);
                        writeStringDefinition(out, n.desc);
                    } else if (insnNode instanceof FieldInsnNode) {
                        FieldInsnNode n = (FieldInsnNode) insnNode;
                        writeStringDefinition(out, n.owner);
                        writeStringDefinition(out, LlvmUtil.mangleString(n.owner));
                        writeStringDefinition(out, n.name);
                        writeStringDefinition(out, n.desc);
                    } else if (insnNode instanceof TypeInsnNode) {
                        TypeInsnNode n = (TypeInsnNode) insnNode;
                        writeStringDefinition(out, n.desc);
                        writeStringDefinition(out, LlvmUtil.mangleString(n.desc));
                        if (n.getOpcode() == Opcodes.ANEWARRAY) {
                            writeStringDefinition(out, "[" + Type.getObjectType(n.desc).getDescriptor());
                        }
                    } else if (insnNode instanceof LdcInsnNode) {
                        LdcInsnNode n = (LdcInsnNode) insnNode;
                        if (n.cst instanceof String) {
                            writeStringDefinition(out, (String) n.cst);
                        }
                        if (n.cst instanceof Type) {
                            writeStringDefinition(out, ((Type) n.cst).getInternalName());
                        }
                    } else if (insnNode instanceof MultiANewArrayInsnNode) {
                        MultiANewArrayInsnNode n = (MultiANewArrayInsnNode) insnNode;
                        writeStringDefinition(out, n.desc);
                    }
                }
                
                for (TryCatchBlockNode n : (List<TryCatchBlockNode>) node.tryCatchBlocks) {
                    if (n.type != null) {
                        writeStringDefinition(out, n.type);
                        writeStringDefinition(out, LlvmUtil.mangleString(n.type));
                    }
                }
            } else {
                writeStringDefinition(out, LlvmUtil.mangleNativeMethodShort(classNode, node));
                writeStringDefinition(out, LlvmUtil.mangleNativeMethodLong(classNode, node));
            }
        }
        out.println();
        
        out.println("; Field accessors");
        Set<String> resCommon = new HashSet<String>();
        Set<String> accessors = new HashSet<String>();
        for (MethodNode node : (List<MethodNode>) classNode.methods) {
            if (!LlvmUtil.isNative(node)) {
                for (AbstractInsnNode insnNode : node.instructions.toArray()) {
                    if (insnNode instanceof FieldInsnNode) {
                        FieldInsnNode n = (FieldInsnNode) insnNode;
                        
                        String fieldName = LlvmUtil.mangleString(n.owner) + "_" + LlvmUtil.mangleString(n.name) + "__" + LlvmUtil.mangleString(n.desc);
                        String commonPrefix = (new String[] {"GetPutStatic", "GetPutStatic", "GetPutField", "GetPutField"})[n.getOpcode() - Opcodes.GETSTATIC];
                        String prefix = (new String[] {"GetStatic", "PutStatic", "GetField", "PutField"})[n.getOpcode() - Opcodes.GETSTATIC];
                        String commonVarName = commonPrefix + "_" + fieldName + "_Common";
                        String varName = prefix + "_" + fieldName;
                        if (!accessors.contains(varName)) {
                            switch (n.getOpcode()) { 
                            case Opcodes.GETSTATIC:
                                if (!resCommon.contains(commonVarName)) {
                                    out.format("@%s = linker_private global %%GetPutStaticCommon {void ()* @_nvmBcResolveFieldForGetPutStaticCommon, i8* null, i8* %s, i8* %s, i8* %s}\n", 
                                            commonVarName, LlvmUtil.getStringReference(n.owner), 
                                            LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                    resCommon.add(commonVarName);
                                }
                                out.format("@%s = private global %%GetStatic {void ()* @_nvmBcResolveFieldForGetStatic0, %%GetPutStaticCommon* @%s, %%Class** @clazz, i8* null}\n", 
                                        varName, commonVarName);
                                break;
                            case Opcodes.PUTSTATIC:
                                if (!resCommon.contains(commonVarName)) {
                                    out.format("@%s = linker_private global %%GetPutStaticCommon {void ()* @_nvmBcResolveFieldForGetPutStaticCommon, i8* null, i8* %s, i8* %s, i8* %s}\n", 
                                            commonVarName, LlvmUtil.getStringReference(n.owner), 
                                            LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                    resCommon.add(commonVarName);
                                }
                                out.format("@%s = private global %%PutStatic {void ()* @_nvmBcResolveFieldForPutStatic0, %%GetPutStaticCommon* @%s, %%Class** @clazz, i8* null}\n", 
                                        varName, commonVarName);
                                break;
                            case Opcodes.GETFIELD:
                                if (!resCommon.contains(commonVarName)) {
                                    out.format("@%s = linker_private global %%GetPutFieldCommon {void ()* @_nvmBcResolveFieldForGetPutFieldCommon, i32 0, i8* %s, i8* %s, i8* %s}\n", 
                                            commonVarName, LlvmUtil.getStringReference(n.owner), 
                                            LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                    resCommon.add(commonVarName);
                                }
                                out.format("@%s = private global %%GetField {void ()* @_nvmBcResolveFieldForGetField0, %%GetPutFieldCommon* @%s, %%Class** @clazz, i32 0}\n", 
                                        varName, commonVarName);
                                break;
                            case Opcodes.PUTFIELD:
                                if (!resCommon.contains(commonVarName)) {
                                    out.format("@%s = linker_private global %%GetPutFieldCommon {void ()* @_nvmBcResolveFieldForGetPutFieldCommon, i32 0, i8* %s, i8* %s, i8* %s}\n", 
                                            commonVarName, LlvmUtil.getStringReference(n.owner), 
                                            LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                    resCommon.add(commonVarName);
                                }
                                out.format("@%s = private global %%PutField {void ()* @_nvmBcResolveFieldForPutField0, %%GetPutFieldCommon* @%s, %%Class** @clazz, i32 0}\n", 
                                        varName, commonVarName);
                                break;
                            }
                            accessors.add(varName);
                        }
               
                    }
                }
            }
        }
        out.println();
        
        out.println("; Method lookup function pointers");
        Set<String> functions = new HashSet<String>();
        for (MethodNode node : (List<MethodNode>) classNode.methods) {
            if (!LlvmUtil.isNative(node)) {
                for (AbstractInsnNode insnNode : node.instructions.toArray()) {
                    if (insnNode instanceof MethodInsnNode) {
                        MethodInsnNode n = (MethodInsnNode) insnNode;
                        if (n.owner.equals(classNode.name)) {
                            MethodNode mnode = LlvmUtil.findMethodNode(classNode, n.name, n.desc);
                            if (mnode != null && ((classNode.access & Opcodes.ACC_STATIC) > 0 || "<init>".equals(mnode.name) || (mnode.access & Opcodes.ACC_PRIVATE) > 0 || (mnode.access & Opcodes.ACC_FINAL) > 0 || (n.getOpcode() == Opcodes.INVOKESTATIC && (mnode.access & Opcodes.ACC_STATIC) > 0))) {
                                // Constructors as well as private, final and static methods of the current class will be called directly
                                continue;
                            }
                        }
                        
                        String prefix = (new String[] {"InvokeVirtual", "InvokeSpecial", "InvokeStatic", "InvokeInterface"})[n.getOpcode() - Opcodes.INVOKEVIRTUAL];
                        String mangledMethod = LlvmUtil.mangleMethod(n.owner, n.name, n.desc);
                        String varName = prefix + "_" + mangledMethod;
                        if (!functions.contains(varName)) {
                        
                            switch (n.getOpcode()) { 
                            case Opcodes.INVOKESTATIC:
                                out.format("@%s_Common = linker_private global %%InvokeStaticCommon {void ()* @_nvmBcResolveMethodForInvokeStaticCommon, i8* %s, i8* %s, i8* %s, i8* null}\n", 
                                        varName, LlvmUtil.getStringReference(n.owner), 
                                        LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                out.format("@%s = private global %%InvokeStatic {void ()* @_nvmBcResolveMethodForInvokeStatic0, %%InvokeStaticCommon* @%s_Common, %%Class** @clazz}\n", 
                                        varName, varName);
                                break;
                            case Opcodes.INVOKEVIRTUAL:
                                out.format("@%s_Common = linker_private global %%InvokeVirtualCommon {void ()* @_nvmBcResolveMethodForInvokeVirtualCommon, i8* %s, i8* %s, i8* %s, i8* null, i32 0}\n", 
                                        varName, LlvmUtil.getStringReference(n.owner), 
                                        LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                out.format("@%s = private global %%InvokeVirtual {void ()* @_nvmBcResolveMethodForInvokeVirtual0, %%InvokeVirtualCommon* @%s_Common, %%Class** @clazz, i32 0}\n", 
                                        varName, varName);
                                break;
                            case Opcodes.INVOKEINTERFACE:
                                out.format("@%s_Common = linker_private global %%InvokeInterfaceCommon {void ()* @_nvmBcResolveMethodForInvokeInterfaceCommon, i8* %s, i8* %s, i8* %s, i8* null}\n", 
                                        varName, LlvmUtil.getStringReference(n.owner), 
                                        LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                out.format("@%s = private global %%InvokeInterface {void ()* @_nvmBcResolveMethodForInvokeInterface0, %%InvokeInterfaceCommon* @%s_Common, %%Class** @clazz}\n", 
                                        varName, varName);
                                break;
                            case Opcodes.INVOKESPECIAL:
                                out.format("@%s_Common = linker_private global %%InvokeSpecialCommon {void ()* @_nvmBcResolveMethodForInvokeSpecialCommon, i8* %s, i8* %s, i8* %s, i8* null}\n", 
                                        varName, LlvmUtil.getStringReference(n.owner), 
                                        LlvmUtil.getStringReference(n.name), LlvmUtil.getStringReference(n.desc));
                                out.format("@%s = private global %%InvokeSpecial {void ()* @_nvmBcResolveMethodForInvokeSpecial0, %%InvokeSpecialCommon* @%s_Common, %%Class** @clazz}\n", 
                                        varName, varName);
                                break;
                            default:
                                throw new RuntimeException();
                            }
                            
                            functions.add(varName);
                        }
                    }
                }
            }
        }
        
        out.println("; CHECKCAST / INSTANCEOF functions");
        for (MethodNode node : (List<MethodNode>) classNode.methods) {
            if (!LlvmUtil.isNative(node)) {
                for (AbstractInsnNode insnNode : node.instructions.toArray()) {
                    if (insnNode instanceof TypeInsnNode) {
                        TypeInsnNode n = (TypeInsnNode) insnNode;
                        if (n.getOpcode() != Opcodes.CHECKCAST && n.getOpcode() != Opcodes.INSTANCEOF && n.getOpcode() != Opcodes.NEW) {
                            continue;
                        }
                        
                        String prefix = null;
                        if (n.getOpcode() == Opcodes.CHECKCAST) {
                            prefix = "Checkcast";
                        }
                        if (n.getOpcode() == Opcodes.INSTANCEOF) {
                            prefix = "Instanceof";
                        }
                        if (n.getOpcode() == Opcodes.NEW) {
                            prefix = "New";
                        }
                        String mangledClass = LlvmUtil.mangleString(n.desc);
                        String varName = prefix + "_" + mangledClass;
                        if (!functions.contains(varName)) {
                            String commonVarName = "ClassResCommon" + "_" + mangledClass;
                            
                            if (!resCommon.contains(commonVarName)) {
                                out.format("@%s = linker_private global %%ClassResCommon {void ()* @_nvmBcResolveClassResCommon, i8* %s, %%Class* null}\n", 
                                        commonVarName, LlvmUtil.getStringReference(n.desc));
                                resCommon.add(commonVarName);
                            }
                            out.format("@%s = private global %%%sRes {void ()* @_nvmBcResolveClassFor%s0, %%ClassResCommon* @%s, %%Class** @clazz}\n", 
                                    varName, prefix, prefix, commonVarName);
                            functions.add(varName);
                        }                        

                    }
                }
            }
        }
        
        out.println("; Function declarations");
        
        for (MethodNode node : (List<MethodNode>) classNode.methods) {
            out.println(LlvmUtil.javaMethodToLlvmFunctionDeclaration(classNode, node));
        }
        out.println();
        
        Set<String> throwables = new HashSet<String>();
        for (MethodNode node : (List<MethodNode>) classNode.methods) {
            if (!LlvmUtil.isNative(node)) {
                for (TryCatchBlockNode n : (List<TryCatchBlockNode>) node.tryCatchBlocks) {
                    if (n.type != null && !throwables.contains(n.type)) {
                        out.format("@\"%s_%%Class*\" = private global %%Class* null\n", LlvmUtil.mangleString(n.type));
                        throwables.add(n.type);
                    }
                }
            }
        }
        
        out.println("@clazz = private global %Class* null");
        
        for (MethodNode node : (List<MethodNode>) classNode.methods) {
            if (!LlvmUtil.isNative(node)) {
                if ((node.access & Opcodes.ACC_ABSTRACT) == 0) {
                    new MethodCompiler(classNode, node).write(out);
                }
            } else {
                String function = LlvmUtil.mangleMethod(classNode, node);
                boolean ztatic = (node.access & Opcodes.ACC_STATIC) > 0;
                String llvmReturnType = LlvmUtil.javaTypeToLlvmType(Type.getReturnType(node.desc));
                
                String varName = "InvokeNative_" + function;
                out.format("@%s = private global %%InvokeNative {void (%%InvokeNative*)* @_nvmBcResolveNativeMethod, void ()* null, i8* %s, i8* %s}\n", 
                        varName, 
                        LlvmUtil.getStringReference(LlvmUtil.mangleNativeMethodShort(classNode, node)), 
                        LlvmUtil.getStringReference(LlvmUtil.mangleNativeMethodLong(classNode, node)));

                out.format("define %s {\n", LlvmUtil.functionDefinition(LlvmUtil.mangleMethod(classNode, node), node.desc, ztatic));
                if (ztatic) {
                    out.format("    %%clazz = load %%Class** @clazz\n");
                }
                out.format("    %%resolve = load void (%%InvokeNative*)** getelementptr(%%InvokeNative* @%s, i32 0, i32 0)\n", varName);
                out.format("    call void %%resolve(%%InvokeNative* @%s)\n", varName);
                out.format("    %%ptr0 = load void ()** getelementptr(%%InvokeNative* @%s, i32 0, i32 1)\n", varName);
                out.format("    %%function = bitcast void ()* %%ptr0 to %s*\n", LlvmUtil.nativeFunctionType(node.desc, ztatic));
                List<String> args = LlvmUtil.nativeDescToCallArgs(node.desc, ztatic, false);
                if (Type.getReturnType(node.desc) == Type.VOID_TYPE) {
                    out.format("    call void %%function(%s)\n", LlvmUtil.join(args));
                    out.format("    call void @_nvmBcThrowIfExceptionOccurred(%%Env* %s)\n", new Var("env", "%Env*"));
                    out.format("    ret void\n");
                } else {
                    out.format("    %%res = call %s %%function(%s)\n", llvmReturnType, LlvmUtil.join(args));
                    out.format("    call void @_nvmBcThrowIfExceptionOccurred(%%Env* %s)\n", new Var("env", "%Env*"));
                    out.format("    ret %s %%res\n", llvmReturnType);
                }
                out.format("}\n");
            }
        }
        out.println();
        
        out.format( "define %%Class* @\"NullVM_%s\"(%%Env* %%env) {\n", LlvmUtil.mangleString(classNode.name));
        if (!classFields.isEmpty()) {
            out.format("    %%ClassDataSize = getelementptr %%ClassFields* null, i32 1\n"); 
            out.format("    %%ClassDataSizeI = ptrtoint %%ClassFields* %%ClassDataSize to i32\n"); 
        } else {
            out.format("    %%ClassDataSizeI = bitcast i32 0 to i32\n"); 
        }
        if (!instanceFields.isEmpty()) {
            out.format("    %%InstanceDataSize = getelementptr %%InstanceFields* null, i32 1\n"); 
            out.format("    %%InstanceDataSizeI = ptrtoint %%InstanceFields* %%InstanceDataSize to i32\n"); 
        } else {
            out.format("    %%InstanceDataSizeI = bitcast i32 0 to i32\n"); 
        }

        out.format("    %%clazz = invoke %%Class* @_nvmBcAllocateClass(%%Env* %%env, i8* %s, i8* %s, i32 %d, i32 %%ClassDataSizeI, i32 %%InstanceDataSizeI)" 
                + " to label %%AllocateClassSuccess unwind label %%CatchAll\n",
                LlvmUtil.getStringReference(classNode.name), 
                classNode.superName != null && (classNode.access & Opcodes.ACC_INTERFACE) == 0 ? LlvmUtil.getStringReference(classNode.superName) : "null", 
                        classNode.access);
        out.format("AllocateClassSuccess:\n");
        
        for (int i = 0; i < classNode.interfaces.size(); i++) {
            String interfaze = (String) classNode.interfaces.get(i);
            out.format("    invoke void @_nvmBcAddInterface(%%Env* %%env, %%Class* %%clazz, i8* %s) to label %%AddInterface%dSuccess unwind label %%CatchAll\n", 
                    LlvmUtil.getStringReference(interfaze), i);
            out.format("AddInterface%dSuccess:\n", i);
        }

        for (int i = 0; i < classNode.methods.size(); i++) {
            int index = classNode.methods.size() - i - 1;
            MethodNode node = (MethodNode) classNode.methods.get(index);
            if ((node.access & Opcodes.ACC_ABSTRACT) != 0) {
                out.format("    %%FuncPtr%d = inttoptr i32 0 to i8*\n", i);
            } else {
                out.format("    %%FuncPtr%d = bitcast %s @%s to i8*\n", i, 
                        LlvmUtil.javaMethodToLlvmFunctionType(node), LlvmUtil.mangleMethod(classNode, node));
            }
            out.format("    invoke void @_nvmBcAddMethod(%%Env* %%env, %%Class* %%clazz, i8* %s, i8* %s, i32 %d, i8* %%FuncPtr%d) to label %%AddMethod%dSuccess unwind label %%CatchAll\n", 
                    LlvmUtil.getStringReference(node.name), LlvmUtil.getStringReference(node.desc), 
                    node.access, i, i);
            out.format("AddMethod%dSuccess:\n", i);
        }

        int classFieldCounter = 0;
        int instanceFieldCounter = 0;
        for (int i = 0; i < classNode.fields.size(); i++) {
            FieldNode node = (FieldNode) classNode.fields.get(i);
            Type t = Type.getType(node.desc);
            String llvmType = LlvmUtil.javaTypeToLlvmType(t);
            if ((node.access & Opcodes.ACC_STATIC) != 0) {
                out.format("    %%ClassFieldOffset%d = getelementptr %%ClassFields* null, i32 0, i32 %d\n", i, classFieldCounter++); 
                out.format("    %%ClassFieldOffset%dI = ptrtoint %s* %%ClassFieldOffset%d to i32\n", i, llvmType, i); 
                out.format("    invoke void @_nvmBcAddField(%%Env* %%env, %%Class* %%clazz, i8* %s, i8* %s, i32 %d, i32 %%ClassFieldOffset%dI) to label %%AddField%dSuccess unwind label %%CatchAll\n", 
                        LlvmUtil.getStringReference(node.name), LlvmUtil.getStringReference(node.desc), 
                        node.access, i, i);
            } else {
                out.format("    %%InstanceFieldOffset%d = getelementptr %%InstanceFields* null, i32 0, i32 %d\n", i, instanceFieldCounter++); 
                out.format("    %%InstanceFieldOffset%dI = ptrtoint %s* %%InstanceFieldOffset%d to i32\n", i, llvmType, i); 
                out.format("    invoke void @_nvmBcAddField(%%Env* %%env, %%Class* %%clazz, i8* %s, i8* %s, i32 %d, i32 %%InstanceFieldOffset%dI) to label %%AddField%dSuccess unwind label %%CatchAll\n", 
                        LlvmUtil.getStringReference(node.name), LlvmUtil.getStringReference(node.desc), 
                        node.access, i, i);
            }
            out.format("AddField%dSuccess:\n", i);
        }
        
        int i = 0;
        for (String throwable : throwables) {
            Var tmp = new Var("throwable" + i++, "%Class*");
            out.format("    %s = invoke %%Class* @_nvmBcFindClass(%%Env* %%env, i8* %s, %%Class* %%clazz) to label %%FindThrowable%dSuccess unwind label %%CatchAll\n", tmp, LlvmUtil.getStringReference(throwable), i);
            out.format("FindThrowable%dSuccess:\n", i);
            out.format("    store %%Class* %s, %%Class** @\"%s_%%Class*\"\n", tmp, LlvmUtil.mangleString(throwable));
        }
        
        out.println("    invoke void @_nvmBcRegisterClass(%Env* %env, %Class* %clazz) to label %RegisterClassSuccess unwind label %CatchAll");
        out.format("RegisterClassSuccess:\n");
        out.println("    store %Class* %clazz, %Class** @clazz");
        out.println("    ret %Class* %clazz");
        out.format("CatchAll:\n");
        out.format("    %%ehptr = call i8* @llvm.eh.exception()\n");
        out.format("    %%sel = call i64 (i8*, i8*, ...)* @llvm.eh.selector.i64(i8* %%ehptr, "
                + "i8* bitcast (i32 (i32, i32, i64, i8*, i8*)* @_nvmPersonality to i8*), i32 1)\n");        
        out.println("    ret %Class* null");
        out.println("}\n");
        
        out.flush();
    }

    private void writeHeader() {
        out.println("%Env = type opaque");
        out.println("%Class = type opaque");
        out.println("%Object = type opaque");
        
        out.println("%ClassResCommon = type {void ()*, i8*, %Class*}");
        out.println("%NewRes = type {void ()*, %ClassResCommon*, %Class**}");
        out.println("%CheckcastRes = type {void ()*, %ClassResCommon*, %Class**}");
        out.println("%InstanceofRes = type {void ()*, %ClassResCommon*, %Class**}");
        out.println("%LdcClassRes = type {void ()*, %ClassResCommon*, %Class**}");
        out.println("%GetPutStaticCommon = type {void ()*, i8*, i8*, i8*, i8*}");
        out.println("%GetStatic = type {void ()*, %GetPutStaticCommon*, %Class**, i8*}");
        out.println("%PutStatic = type {void ()*, %GetPutStaticCommon*, %Class**, i8*}");
        out.println("%GetPutFieldCommon = type {void ()*, i32, i8*, i8*, i8*}");
        out.println("%GetField = type {void ()*, %GetPutFieldCommon*, %Class**, i32}");
        out.println("%PutField = type {void ()*, %GetPutFieldCommon*, %Class**, i32}");
        out.println("%InvokeVirtualCommon = type {void ()*, i8*, i8*, i8*, i8*, i32}");
        out.println("%InvokeVirtual = type {void ()*, %InvokeVirtualCommon*, %Class**, i32}");
        out.println("%InvokeSpecialCommon = type {void ()*, i8*, i8*, i8*, i8*}");
        out.println("%InvokeSpecial = type {void ()*, %InvokeSpecialCommon*, %Class**}");
        out.println("%InvokeStaticCommon = type {void ()*, i8*, i8*, i8*, i8*}");
        out.println("%InvokeStatic = type {void ()*, %InvokeStaticCommon*, %Class**}");
        out.println("%InvokeInterfaceCommon = type {void ()*, i8*, i8*, i8*, i8*}");
        out.println("%InvokeInterface = type {void ()*, %InvokeInterfaceCommon*, %Class**}");
        out.println("%InvokeNative = type {void (%InvokeNative*)*, void ()*, i8*, i8*}");
        
        out.println("declare %Class* @_nvmBcAllocateClass(%Env*, i8*, i8*, i32, i32, i32)");
        out.println("declare %Class* @_nvmBcAllocateSystemClass(%Env*, i8*, i8*, i32, i32, i32)");
        out.println("declare void @_nvmBcAddInterface(%Env*, %Class*, i8*)");
        out.println("declare void @_nvmBcAddMethod(%Env*, %Class*, i8*, i8*, i32, i8*)");
        out.println("declare void @_nvmBcAddField(%Env*, %Class*, i8*, i8*, i32, i32)");
        out.println("declare void @_nvmBcRegisterClass(%Env*, %Class*)");
        out.println("declare %Class* @_nvmBcFindClass(%Env*, i8*, %Class*)");
        out.println("declare void @_nvmBcThrow(%Env*, %Object*)");
        out.println("declare void @_nvmBcThrowIfExceptionOccurred(%Env*)");
        out.println("declare void @_nvmBcThrowNullPointerException(%Env*)");
        out.println("declare void @_nvmBcThrowArrayIndexOutOfBoundsException(%Env*, i32)");
        out.println("declare void @_nvmBcThrowVerifyError(%Env*, i8*)");
        out.println("declare %Object* @_nvmBcExceptionClear(%Env*)");
        out.println("declare i32 @_nvmBcExceptionMatch(%Env*, %Object*, %Class*)");
        
        out.println("declare %Object* @_nvmBcNewBooleanArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewByteArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewCharArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewShortArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewIntArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewLongArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewFloatArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewDoubleArray(%Env*, i32)");
        out.println("declare %Object* @_nvmBcNewObjectArray(%Env*, i32, i8*, %Class*)");
        out.println("declare %Object* @_nvmBcNewMultiArray(%Env*, i32, i32*, i8*, %Class*)");
        
        out.println("declare %Object* @_nvmBcNewStringAscii(%Env*, i8*)");
        out.println("declare %Object* @_nvmBcLdcClass(%Env*, i8*, %Class*)");
        
        out.println("declare void @_nvmBcResolveClassResCommon()");
        out.println("declare void @_nvmBcResolveClassForNew0()");
        out.println("declare void @_nvmBcResolveClassForCheckcast0()");
        out.println("declare void @_nvmBcResolveClassForInstanceof0()");
        
        out.println("declare void @_nvmBcResolveFieldForGetPutStaticCommon()");
        out.println("declare void @_nvmBcResolveFieldForGetStatic0()");
        out.println("declare void @_nvmBcResolveFieldForPutStatic0()");
        out.println("declare void @_nvmBcResolveFieldForGetPutFieldCommon()");
        out.println("declare void @_nvmBcResolveFieldForGetField0()");
        out.println("declare void @_nvmBcResolveFieldForPutField0()");
        
        out.println("declare void @_nvmBcResolveMethodForInvokeStaticCommon()");
        out.println("declare void @_nvmBcResolveMethodForInvokeStatic0()");
        out.println("declare void @_nvmBcResolveMethodForInvokeVirtualCommon()");
        out.println("declare void @_nvmBcResolveMethodForInvokeVirtual0()");
        out.println("declare void @_nvmBcResolveMethodForInvokeSpecialCommon()");
        out.println("declare void @_nvmBcResolveMethodForInvokeSpecial0()");
        out.println("declare void @_nvmBcResolveMethodForInvokeInterfaceCommon()");
        out.println("declare void @_nvmBcResolveMethodForInvokeInterface0()");
        out.println("declare void @_nvmBcResolveNativeMethod(%InvokeNative*)");

        out.println("declare void @_nvmBcMonitorEnter(%Env*, %Object*)");
        out.println("declare void @_nvmBcMonitorExit(%Env*, %Object*)");
        
        out.println("declare i8* @llvm.eh.exception() nounwind");
        out.println("declare i64 @llvm.eh.selector.i64(i8*, i8*, ...) nounwind");
        out.println("declare i32 @_nvmPersonality(i32, i32, i64, i8*, i8*)");
        out.println("declare i32 @j_arraylength(%Object*)");
        out.println("declare i32 @j_iaload(%Object* %o, i32 %index)");
        out.println("declare void @j_iastore(%Object* %o, i32 %index, i32 %value)");
        out.println("declare i32 @j_baload(%Object* %o, i32 %index)");
        out.println("declare void @j_bastore(%Object* %o, i32 %index, i32 %value)");
        out.println("declare i32 @j_saload(%Object* %o, i32 %index)");
        out.println("declare void @j_sastore(%Object* %o, i32 %index, i32 %value)");
        out.println("declare i32 @j_caload(%Object* %o, i32 %index)");
        out.println("declare void @j_castore(%Object* %o, i32 %index, i32 %value)");
        out.println("declare float @j_faload(%Object* %o, i32 %index)");
        out.println("declare void @j_fastore(%Object* %o, i32 %index, float %value)");
        out.println("declare i64 @j_laload(%Object* %o, i32 %index)");
        out.println("declare void @j_lastore(%Object* %o, i32 %index, i64 %value)");
        out.println("declare double @j_daload(%Object* %o, i32 %index)");
        out.println("declare void @j_dastore(%Object* %o, i32 %index, double %value)");
        out.println("declare %Object* @j_aaload(%Object* %o, i32 %index)");
        out.println("declare void @j_aastore(%Object* %o, i32 %index, %Object* %value)");
        out.println();
    }

    private void writeStringDefinition(PrintWriter out, String s) {
        if (!strings.contains(s)) {
            out.format("%s = linker_private constant %s %s\n", LlvmUtil.getStringVar(s), 
                    LlvmUtil.getStringType(s), LlvmUtil.getStringValue(s));
            strings.add(s);
        }
    }
}
