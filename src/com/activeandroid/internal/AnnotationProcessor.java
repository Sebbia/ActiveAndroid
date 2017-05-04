package com.activeandroid.internal;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

import com.activeandroid.Model;
import com.activeandroid.annotation.Column;

public final class AnnotationProcessor extends AbstractProcessor {

	private static final String MODEL = "model";
	private static final String CURSOR = "cursor";
	private static final String CONTENT_VALUES = "contentValues";
	private static final String COLUMNS_ORDERED = "columnsOrdered";

	private RoundEnvironment env;

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
		this.env = env;
		if (annotations.size() > 0) {
			parseColumns();
		}
		return true;
	}

	@Override
	public Set<String> getSupportedAnnotationTypes() {
		Set<String> supportedTypes = new HashSet<String>();
		supportedTypes.add(Column.class.getCanonicalName());
		return supportedTypes;
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	private void parseColumns() {
		Set<? extends Element> columns = env.getElementsAnnotatedWith(Column.class);
		Map<TypeElement, Set<VariableElement>> tables = new HashMap<TypeElement, Set<VariableElement>>();
		for (Element element : columns) {
			if (element instanceof VariableElement == false || element.getKind() != ElementKind.FIELD) {
				error("@Column annotation should be applied only to local variables", element);
				continue;
			}
			VariableElement columnElement = (VariableElement) element;

			if (checkColumnModifiers(columnElement) == false)
				continue;

			TypeElement tableElement = null;
			if (element.getEnclosingElement() instanceof TypeElement)
				tableElement = (TypeElement) element.getEnclosingElement();
			else
				error("@Column annotation located not inside of class", element);

			if (checkTableModifiers(tableElement) == false)
				continue;

			Set<VariableElement> columnsElements = tables.get(tableElement);
			if (columnsElements == null) {
				columnsElements = new HashSet<VariableElement>();
				tables.put(tableElement, columnsElements);
			}

			columnsElements.add(columnElement);

		}

		for (TypeElement table : tables.keySet()) {
			generate(table, tables.get(table));
		}
	}

	private void generate(TypeElement tableElement, Set<VariableElement> columns) {
		String packageName = processingEnv.getElementUtils().getPackageOf(tableElement).getQualifiedName().toString();
		String className = tableElement.getQualifiedName().toString();
		String fillerClassName = getClassName(tableElement, packageName) + ModelFiller.SUFFIX;

		try {
			JavaFileObject jfo = processingEnv.getFiler().createSourceFile(packageName + "." + fillerClassName, tableElement);
			Writer writer = jfo.openWriter();
			writer.write("//Generated by ActiveAndroid. Do not modify\n");
			writer.write("package " + packageName + ";\n\n");
			writer.write("import java.util.ArrayList;\n");
			writer.write("import java.util.Arrays;\n");
			writer.write("import java.util.List;\n\n");

			writer.write("import com.activeandroid.internal.ModelHelper;\n");
			writer.write("import com.activeandroid.internal.ModelFiller;\n");
			writer.write("\n");
			writer.write("public class " + fillerClassName + " extends ModelFiller {\n\n");
			writer.write("  public void loadFromCursor(com.activeandroid.Model genericModel, android.database.Cursor " + CURSOR + ") {\n");
			writer.write("    if (superModelFiller != null)\n");
			writer.write("       superModelFiller.loadFromCursor(genericModel, " + CURSOR + ");\n");
			writer.write("    List<String> " + COLUMNS_ORDERED + " = new ArrayList<String>(Arrays.asList(" + CURSOR + ".getColumnNames()));\n");
			writer.write("    " + className + " " + MODEL + " = (" + className + ") genericModel;\n");
			writer.write(getLoadFromCursorCode(columns));
			writer.write("  }\n\n");

			writer.write("  ");
			writer.write("public void fillContentValues(com.activeandroid.Model genericModel, android.content.ContentValues " + CONTENT_VALUES + ") {\n");
			writer.write("    if (superModelFiller != null)\n");
			writer.write("       superModelFiller.fillContentValues(genericModel, " + CONTENT_VALUES + ");\n");
			writer.write("    " + className + " " + MODEL + " = (" + className + ") genericModel;\n");
			writer.write(getFillContentValuesCode(columns));
			writer.write("  }\n");

			writer.write("}");
			writer.flush();
			writer.close();
		} catch (IOException exception) {
			processingEnv.getMessager().printMessage(Kind.ERROR, exception.getMessage());
		}
	}

	private String getLoadFromCursorCode(Set<VariableElement> columns) {
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("    int i = -1; // column index \n");
		final String nullCheck = CURSOR + ".isNull(i) ? null : ";
		for (VariableElement column : columns) {
			Column annotation = column.getAnnotation(Column.class);

			String fieldName = annotation.name();

			if (fieldName == null || fieldName.isEmpty())
				fieldName = column.getSimpleName().toString();

			TypeMirror typeMirror = column.asType();
			boolean notPrimitiveType = typeMirror instanceof DeclaredType;
			String type = typeMirror.toString() + ".class";
			String getColumnIndex = COLUMNS_ORDERED + ".indexOf(\"" + fieldName + "\")";
			String getColumnIndexAssignment = "i = " + getColumnIndex + "; \n";

			stringBuilder.append("    " + getColumnIndexAssignment );
			if (notPrimitiveType) {
				stringBuilder.append("    if (ModelHelper.isSerializable(" + type + ")) {\n");
				stringBuilder.append("      " + MODEL + "." + column.getSimpleName() + " = (" + typeMirror.toString() + ") ModelHelper.getSerializable(cursor, " + type + ", i);\n");
				stringBuilder.append("    } else {\n");
				stringBuilder.append("      " + MODEL + "." + column.getSimpleName() + " = ");
			} else {
				stringBuilder.append("    " + MODEL + "." + column.getSimpleName() + " = ");
			}

			if (isTypeOf(typeMirror, Integer.class) || isTypeOf(typeMirror, Byte.class) || isTypeOf(typeMirror, Short.class) )
				stringBuilder.append(nullCheck).append(CURSOR + ".getInt(i);\n");
			else if (isTypeOf(typeMirror, Long.class))
				stringBuilder.append(nullCheck).append(CURSOR + ".getLong(i);\n");
			else if (isTypeOf(typeMirror, Float.class))
				stringBuilder.append(nullCheck).append(CURSOR + ".getFloat(i);\n");
			else if (isTypeOf(typeMirror, Double.class))
				stringBuilder.append(nullCheck).append(CURSOR + ".getDouble(i);\n");
			else if (isTypeOf(typeMirror, int.class))
				stringBuilder.append(CURSOR + ".getInt(i);\n");
			else if (isTypeOf(typeMirror, byte.class))
				stringBuilder.append(CURSOR + ".getInt(i);\n");
			else if (isTypeOf(typeMirror, short.class))
				stringBuilder.append(CURSOR + ".getInt(i);\n");
			else if (isTypeOf(typeMirror, long.class))
				stringBuilder.append(CURSOR + ".getLong(i);\n");
			else if (isTypeOf(typeMirror, float.class))
				stringBuilder.append(CURSOR + ".getFloat(i);\n");
			else if (isTypeOf(typeMirror, double.class))
				stringBuilder.append(CURSOR + ".getDouble(i);\n");
			else if (isTypeOf(typeMirror, Boolean.class))
				stringBuilder.append(nullCheck).append(CURSOR + ".getInt(i) != 0;\n");
			else if (isTypeOf(typeMirror, boolean.class))
				stringBuilder.append(CURSOR + ".getInt(i) != 0;\n");
			else if (isTypeOf(typeMirror, char.class))
				stringBuilder.append(CURSOR + ".getString(i);\n");
			else if (isTypeOf(typeMirror, Character.class))
				stringBuilder.append(nullCheck).append(CURSOR + ".getString(i);\n");
			else if (isTypeOf(typeMirror, String.class))
				stringBuilder.append(nullCheck).append(CURSOR + ".getString(i);\n");
			else if (isTypeOf(typeMirror, byte[].class))
				stringBuilder.append(CURSOR + ".getBlob(i);\n");
			else if (isTypeOf(typeMirror, Byte[].class))
				stringBuilder.append(nullCheck).append(CURSOR + ".getBlob(i);\n");
			else if (isTypeOf(typeMirror, Model.class))
				stringBuilder.append("(" + typeMirror.toString() + ") ModelHelper.getModel(cursor, " + type + ", i);\n");
			else if (isTypeOf(typeMirror, Enum.class))
				stringBuilder.append("(" + typeMirror.toString() + ") ModelHelper.getEnum(cursor, " + type + ", i);\n");
			else
				stringBuilder.append(" null;\n");
			if (notPrimitiveType) {
				stringBuilder.append("    }\n");
			}
		}
		return stringBuilder.toString();
	}

	private String getFillContentValuesCode(Set<VariableElement> columns) {
		StringBuilder stringBuilder = new StringBuilder();

		for (VariableElement column : columns) {
			Column annotation = column.getAnnotation(Column.class);

			String fieldName = annotation.name();

			if (fieldName == null || fieldName.isEmpty())
				fieldName = column.getSimpleName().toString();
			
			TypeMirror typeMirror = column.asType();
			boolean notPrimitiveType = typeMirror instanceof DeclaredType;
			String type = typeMirror.toString() + ".class";
			String getValue = MODEL + "." + column.getSimpleName();
			
			if (notPrimitiveType) {
				stringBuilder.append("    if (ModelHelper.isSerializable(" + type + ")) {\n");
				stringBuilder.append("      ModelHelper.setSerializable(" + CONTENT_VALUES + ", " + type + ", " + getValue + ", \"" + fieldName + "\");\n");  
				stringBuilder.append("    } else if (" + getValue + " != null) {\n");
				stringBuilder.append("      " + CONTENT_VALUES + ".");
			} else {
				stringBuilder.append("    " + CONTENT_VALUES + ".");
			}
			if (isTypeOf(typeMirror, Integer.class) || isTypeOf(typeMirror, int.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Byte.class) || isTypeOf(typeMirror, byte.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Short.class) || isTypeOf(typeMirror, short.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Long.class) || isTypeOf(typeMirror, long.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Float.class) || isTypeOf(typeMirror, float.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Double.class) || isTypeOf(typeMirror, double.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Boolean.class) || isTypeOf(typeMirror, boolean.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Character.class) || isTypeOf(typeMirror, char.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ".toString());\n");
			else if (isTypeOf(typeMirror, String.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ".toString());\n");
			else if (isTypeOf(typeMirror, Byte[].class) || isTypeOf(typeMirror, byte[].class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ");\n");
			else if (isTypeOf(typeMirror, Model.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ".getId());\n");
			else if (isTypeOf(typeMirror, Enum.class))
				stringBuilder.append("put(\"" + fieldName + "\", " + getValue + ".name());\n");
			else
				stringBuilder.append("putNull(\"" + fieldName + "\");\n");
			if (notPrimitiveType) {
				if (annotation.defaultValue() == null || annotation.defaultValue().isEmpty()) {
					stringBuilder.append("    } else {\n");
					stringBuilder.append("      " + CONTENT_VALUES + ".putNull(\"" + fieldName +  "\");\n");
				}
				stringBuilder.append("    }\n");
			}
			
		}
		return stringBuilder.toString();
	}

	private boolean isTypeOf(TypeMirror typeMirror, Class<?> type) {
		if (type.getName().equals(typeMirror.toString()))
			return true;

		if (typeMirror instanceof DeclaredType == false)
			return false;

		DeclaredType declaredType = (DeclaredType) typeMirror;
		Element element = declaredType.asElement();
		if (element instanceof TypeElement == false)
			return false;

		TypeElement typeElement = (TypeElement) element;
		TypeMirror superType = typeElement.getSuperclass();
		if (isTypeOf(superType, type))
			return true;
		return false;
	}

	private boolean checkTableModifiers(TypeElement table) {
		if (table.getModifiers().contains(Modifier.PRIVATE)) {
			error("Classes marked with @Table cannot be private", table);
			return false;
		}

		if (table.getKind() != ElementKind.CLASS) {
			error("Only classes can be marked with @Table annotation", table);
			return false;
		}

		return true;
	}

	private boolean checkColumnModifiers(VariableElement column) {

		if (column.getModifiers().contains(Modifier.PRIVATE)) {
			error("Field marked with @Column cannot be private", column);
			return false;
		}

		if (column.getModifiers().contains(Modifier.FINAL)) {
			error("Field marked with @Column cannot be final", column);
			return false;
		}

		if (column.getModifiers().contains(Modifier.STATIC)) {
			error("Field marked with @Column cannot be static", column);
			return false;
		}

		return true;
	}

	private void error(String message, Element element) {
		processingEnv.getMessager().printMessage(Kind.ERROR, message, element);
	}

	private static String getClassName(TypeElement type, String packageName) {
		int packageLen = packageName.length() + 1;
		return type.getQualifiedName().toString().substring(packageLen).replace('.', '$');
	}
}
