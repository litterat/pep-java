/*
 * Copyright (c) 2020, Live Media Pty. Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.litterat.pep.describe;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import io.litterat.pep.Field;
import io.litterat.pep.PepContext;
import io.litterat.pep.PepException;

public class ImmutableFinder implements ComponentFinder {

	@SuppressWarnings("unused")
	private final PepContext context;

	public ImmutableFinder(PepContext context) {
		this.context = context;
	}

	/**
	 * This attempts to match up the arguments of the given constructor with field accessors.
	 * It relies on both the accessors and constructor to be using simple set and get field
	 * with no changes to values.
	 */
	@Override
	public void findComponents(Class<?> clss, Constructor<?> constructor, List<ComponentInfo> fields) throws PepException {

		try {
			ClassReader cr = new ClassReader(clss.getName());
			ClassNode classNode = new ClassNode();
			cr.accept(classNode, 0);

			List<ComponentInfo> immutableFields = new ArrayList<>(constructor.getParameterCount());

			// First try and identify the constructor arguments.
			// Perform node instruction inspection to match constructor arguments with accessors.
			for (MethodNode method : classNode.methods) {

				Type methodType = Type.getType(method.desc);
				String constructorDescriptor = Type.getConstructorDescriptor(constructor);

				// Find the MethodNode that matches the passed in constructor.
				if (method.name.equals("<init>") && method.desc.equals(constructorDescriptor)) {
					identifyArguments(constructor, immutableFields, method, methodType);
					break;
				}

			}

			// if byte code analysis failed or if user supplies @Field annotations.
			Parameter[] params = constructor.getParameters();
			for (int x = 0; x < params.length; x++) {
				Field field = params[x].getAnnotation(Field.class);
				if (field != null) {
					// field has been annotated so check for matching field.
					final int paramIndex = x;
					ComponentInfo component = immutableFields.stream().filter(e -> e.getConstructorArgument() == paramIndex).findFirst().orElse(null);
					if (component != null) {
						// renaming the field.
						component.setName(field.name());
					} else {
						// Add the parameter.
						component = new ComponentInfo(field.name(), params[x].getType());
						component.setConstructorArgument(x);

						immutableFields.add(component);
					}
				}
			}

			// Perform node instruction inspection to match constructor arguments with accessors.
			for (MethodNode methodNode : classNode.methods) {

				Type methodType = Type.getType(methodNode.desc);

				// Only interested in accessors.
				if (methodType.getArgumentTypes().length != 0) {
					continue;
				}

				// Returns the field name that this accessor is using if it is a simple accessor type.
				examineAccessor(clss, immutableFields, methodNode);
			}

			// Possibly failed to find accessor through invariant byte code analysis. 
			// Fallback on @Field annotation or method name.
			for (Method method : clss.getDeclaredMethods()) {
				Field field = method.getAnnotation(Field.class);
				if (field != null) {
					// field has been annotated so check for matching field.
					final String name = field.name();
					ComponentInfo component = immutableFields.stream().filter(e -> e.getName().equals(name)).findFirst().orElse(null);
					if (component != null && component.getReadMethod() == null) {
						component.setReadMethod(method);
						continue;
					}
				}

				String name = method.getName();
				ComponentInfo component = immutableFields.stream().filter(e -> e.getName().equals(name)).findFirst().orElse(null);
				if (component != null && component.getReadMethod() == null) {
					component.setReadMethod(method);
					continue;
				}
			}

			// Fail if we didn't find the right number of parameters.
			if (immutableFields.size() != constructor.getParameterCount()) {
				throw new PepException(String.format("Failed to match immutable fields for class: %s. Add @Field annotations to assist.", clss));
			}

			// Check all params have valid information.
			for (ComponentInfo component : immutableFields) {
				if (component.getReadMethod() == null) {
					throw new PepException(
							String.format("Failed to match immutable field accessor for class: %s. Add @Field annotations to assist.", clss));
				}
			}

			// Add the fields to the list for use.
			fields.addAll(immutableFields);

		} catch (IOException | NoSuchMethodException | SecurityException e) {
			throw new PepException("Failed to access class", e);
		}
	}

	public static Class<?>[] classesFromTypes(Type[] types) throws ClassNotFoundException {
		int len = types.length;
		Class<?>[] argumentTypes = new Class[len];
		for (int i = 0; i < types.length; ++i) {
			argumentTypes[i] = Class.forName(types[i].getClassName());
		}
		return argumentTypes;
	}

	/**
	 * Find all the
	 * 
	 * @param fieldMap
	 * @param method
	 */
	private int identifyArguments(Constructor<?> constructor, List<ComponentInfo> fields, MethodNode method, Type methodType) {
		boolean foundLoadThis = false;
		boolean foundLoadArg = false;
		int arg = 0;
		int argsFound = 0;

		ListIterator<AbstractInsnNode> it = method.instructions.iterator();
		while (it.hasNext()) {
			AbstractInsnNode insn = it.next();

			switch (insn.getOpcode()) {
			case Opcodes.ALOAD:
				VarInsnNode varInsn = (VarInsnNode) insn;
				if (!foundLoadThis) {
					if (varInsn.var == 0) {
						foundLoadThis = true;
					}
					break;
				}
			case Opcodes.ILOAD:
			case Opcodes.LLOAD:
			case Opcodes.DLOAD:
			case Opcodes.FLOAD:
				VarInsnNode varLoadInsn = (VarInsnNode) insn;

				// Check if this is being loaded from a parameter variable.
				if (foundLoadThis & varLoadInsn.var > 0 && varLoadInsn.var <= methodType.getArgumentTypes().length) {
					foundLoadArg = true;
					arg = varLoadInsn.var - 1;
				}
				break;
			case Opcodes.PUTFIELD:
				FieldInsnNode putFieldInsn = (FieldInsnNode) insn;
				if (foundLoadThis && foundLoadArg) {

					// Invariance identified, so capture the argument number.
					Class<?> fieldClass = constructor.getParameters()[arg].getType();
					ComponentInfo component = new ComponentInfo(putFieldInsn.name, fieldClass);
					component.setConstructorArgument(arg);

					fields.add(component);
					argsFound++;

				}
			default:
				foundLoadThis = false;
				foundLoadArg = false;
			}

		}

		return argsFound;

	}

	/**
	 * Look through the instructions looking for ALOAD, GETFIELD, RETURN combination.
	 * 
	 * @param clss
	 * @param fieldMap
	 * @param method
	 * @return field name
	 * @throws NoSuchMethodException
	 */
	private String examineAccessor(Class<?> clss, List<ComponentInfo> fields, MethodNode method) throws NoSuchMethodException {
		boolean foundLoadThis = false;
		String lastField = null;

		ListIterator<AbstractInsnNode> it = method.instructions.iterator();
		while (it.hasNext()) {

			AbstractInsnNode insn = it.next();

			switch (insn.getOpcode()) {
			case Opcodes.ALOAD:
				VarInsnNode varInsn = (VarInsnNode) insn;
				if (varInsn.var == 0) {
					foundLoadThis = true;
				}
				break;
			case Opcodes.GETFIELD:
				FieldInsnNode fieldInsn = (FieldInsnNode) insn;
				if (foundLoadThis) {
					lastField = fieldInsn.name;
				}
				break;
			case Opcodes.IRETURN:
			case Opcodes.ARETURN:
			case Opcodes.LRETURN:
			case Opcodes.DRETURN:
			case Opcodes.RETURN:
			case Opcodes.FRETURN:
				if (foundLoadThis && lastField != null) {

					final String fieldName = lastField;
					ComponentInfo info = fields.stream().filter(e -> e.getName().equals(fieldName)).findFirst().orElse(null);
					if (info != null) {
						info.setReadMethod(clss.getDeclaredMethod(method.name));
					}

					// accessor matches, break;
					break;
				}
			case -1:
				// ignore these opcodes.
				break;
			default:
				foundLoadThis = false;
				lastField = null;
			}
		}

		return lastField;
	}

}
