/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.apollo.amqp.generator;

import com.sun.codemodel.*;
import org.fusesource.fabric.apollo.amqp.jaxb.schema.Descriptor;
import org.fusesource.fabric.apollo.amqp.jaxb.schema.Field;
import org.fusesource.fabric.apollo.amqp.jaxb.schema.Type;
import org.fusesource.hawtbuf.AsciiBuffer;
import org.fusesource.hawtbuf.Buffer;

import java.math.BigInteger;
import java.util.ArrayList;

import static com.sun.codemodel.JExpr.*;
import static org.fusesource.fabric.apollo.amqp.generator.Utilities.sanitize;
import static org.fusesource.fabric.apollo.amqp.generator.Utilities.toJavaClassName;

/**
 *
 */
public class DescribedType  extends AmqpDefinedType {



    class Attribute {
        public String type;
        public JFieldVar attribute;
        public JMethod getter;
        public JMethod setter;
    }

    private JFieldVar SYMBOLIC_ID;
    private JFieldVar SYMBOLIC_ID_SIZE;
    private JFieldVar NUMERIC_ID;
    private JFieldVar NUMERIC_ID_SIZE;

    private JMethod write;
    private JMethod read;
    private JMethod encodeTo;
    private JMethod decodeFrom;
    private JMethod count;
    private JMethod size;

    private ArrayList<Attribute> amqpFields = new ArrayList<Attribute>();

    public DescribedType(Generator generator, String className, Type type) throws JClassAlreadyExistsException {
        super(generator, className, type);
    }

    protected void createInitialFields() {

    }

    protected void createStaticBlock() {
        for ( Object obj : type.getEncodingOrDescriptorOrFieldOrChoiceOrDoc() ) {
            if ( obj instanceof Descriptor ) {
                Descriptor desc = (Descriptor) obj;
                int mods = JMod.PUBLIC | JMod.STATIC | JMod.FINAL;

                SYMBOLIC_ID = cls().field(mods, Buffer.class, "SYMBOLIC_ID", _new(cm.ref(AsciiBuffer.class)).arg(desc.getName()));
                SYMBOLIC_ID_SIZE = cls().field(mods, Long.class, "SYMBOLIC_ID_SIZE", generator.registry().cls().staticInvoke("instance").invoke("sizer").invoke("sizeOfSymbol").arg(ref("SYMBOLIC_ID")));

                String code = desc.getCode();
                String category = code.split(":")[0];
                String descriptorId = code.split(":")[1];
                category = category.substring(2);
                category = category.substring(4);
                descriptorId = descriptorId.substring(2);
                descriptorId = descriptorId.substring(4);

                //CATEGORY = cls().field(mods, long.class, "CATEGORY", JExpr.lit(Integer.parseInt(category.substring(2), 16)));
                //DESCRIPTOR_ID = cls().field(mods, long.class, "DESCRIPTOR_ID", JExpr.lit(Integer.parseInt(descriptorId.substring(2), 16)));
                //NUMERIC_ID = cls().field(mods, cm.LONG, "NUMERIC_ID", JExpr.direct("CATEGORY << 32 | DESCRIPTOR_ID"));
                NUMERIC_ID = cls().field(mods, BigInteger.class, "NUMERIC_ID", _new(cm.ref("java.math.BigInteger")).arg(lit(category + descriptorId)).arg(lit(16)));
                NUMERIC_ID_SIZE = cls().field(mods, Long.class, "NUMERIC_ID_SIZE", generator.registry().cls().staticInvoke("instance").invoke("sizer").invoke("sizeOfULong").arg(ref("NUMERIC_ID")));
                cls().init().add(
                        generator.registry().cls().staticInvoke("instance")
                                .invoke("getFormatCodeMap")
                                .invoke("put")
                                .arg(ref("NUMERIC_ID"))
                                .arg(cls().dotclass())
                );

                cls().init().add(
                        generator.registry().cls().staticInvoke("instance")
                                .invoke("getSymbolicCodeMap")
                                .invoke("put")
                                .arg(ref("SYMBOLIC_ID"))
                                .arg(cls().dotclass())
                );
            }
        }
    }

    public void generateDescribedFields() {
        Log.info("");
        Log.info("Generating %s", cls().binaryName());

        for ( Object obj : type.getEncodingOrDescriptorOrFieldOrChoiceOrDoc() ) {
            if ( obj instanceof Field ) {
                Field field = (Field) obj;
                String fieldType = field.getType();
                String fieldName = sanitize(field.getName());

                Log.info("Field type for field %s : %s", fieldName, fieldType);

                if ( fieldType.equals("*") ) {
                    fieldType = generator.getAmqpBaseType();
                    if ( field.getRequires() != null ) {
                        String requiredType = field.getRequires();
                        if (generator.getProvides().contains(requiredType)) {
                            fieldType = generator.getInterfaces() + "." + toJavaClassName(field.getRequires());
                        }
                    }
                } else if (generator.getDescribed().containsKey(fieldType)) {
                    fieldType = generator.getDescribedJavaClass().get(field.getType());
                } else if (generator.getRestricted().containsKey(fieldType)) {
                    fieldType = generator.getRestrictedMapping().get(field.getType());
                }

                if ( fieldType != null ) {
                    boolean array = false;
                    if ( field.getMultiple() != null && field.getMultiple().equals("true") ) {
                        array = true;
                    }

                    Log.info("Using field type %s", fieldType);

                    Class clazz = generator.getMapping().get(fieldType);
                    JClass c = null;
                    if (fieldType.equals(generator.getAmqpBaseType())) {
                        c = cm.ref(fieldType);
                    } else if ( clazz == null ) {
                        c = cm._getClass(fieldType);
                    } else {
                        if (array) {
                            c = cm.ref(generator.getPrimitiveJavaClass().get(fieldType));
                        } else {
                            c = cm.ref(clazz.getName());
                        }
                    }
                    if ( array ) {
                        c = c.array();
                    }
                    Log.info("%s %s", c.binaryName(), fieldName);
                    Attribute attribute = new Attribute();
                    attribute.attribute = cls().field(JMod.PROTECTED, c, fieldName);

                    attribute.type = fieldType;

                    String doc = field.getName() + ":" + field.getType();

                    if ( field.getLabel() != null ) {
                        doc += " - " + field.getLabel();
                    }
                    attribute.attribute.javadoc().add(doc);

                    attribute.getter = cls().method(JMod.PUBLIC, attribute.attribute.type(), "get" + toJavaClassName(fieldName));
                    attribute.getter.body()._return(_this().ref(attribute.attribute));

                    attribute.setter = cls().method(JMod.PUBLIC, cm.VOID, "set" + toJavaClassName(fieldName));
                    JVar param = attribute.setter.param(attribute.attribute.type(), fieldName);
                    attribute.setter.body().assign(_this().ref(attribute.attribute), param);

                    amqpFields.add(attribute);
                } else {
                    Log.info("Skipping field %s, type not found", field.getName());
                }
            }
        }

        fillInWriteMethod();
        fillInSizeMethod();

        count = cls().method(JMod.PUBLIC, cm.INT, "count");
        count().body()._return(lit(amqpFields.size()));
    }

    private void fillInWriteMethod() {
        writeConstructor().body().block().invoke(ref("out"), "writeByte").arg(generator.registry().cls().staticRef("DESCRIBED_FORMAT_CODE"));
        writeConstructor().body().block().staticInvoke(cm.ref(generator.getPrimitiveJavaClass().get("ulong")), "write").arg(ref("NUMERIC_ID")).arg(ref("out"));
        writeConstructor().body()._return(cast(cm.BYTE, lit(0)));

        write().body().invoke("writeConstructor").arg(ref("out"));
        write().body().invoke("writeBody").arg(cast(cm.BYTE, lit((byte)0))).arg(ref("out"));

        writeBody().body().decl(cm.LONG, "fieldSize", _this().invoke("sizeOfFields"));
        writeBody().body().decl(cm.BYTE, "code", _this().invoke("getListEncoding").arg(ref("fieldSize")));
        writeBody().body().assignPlus(ref("fieldSize"), _this().invoke("getListWidth").arg(ref("formatCode")));

        writeBody().body().invoke(ref("out"), "writeByte").arg(ref("code"));

        JConditional condition = writeBody().body()._if(ref("code").eq(cm.ref("AMQPList").staticRef("LIST_LIST8_CODE")));

        condition._then().invoke(ref("out"), "writeByte").arg(cast(cm.BYTE, ref("fieldSize")));
        condition._then().invoke(ref("out"), "writeByte").arg(cast(cm.BYTE, _this().invoke("count")));

        condition._else().invoke(ref("out"), "writeInt").arg(cast(cm.INT, ref("fieldSize")));
        condition._else().invoke(ref("out"), "writeInt").arg(cast(cm.INT, _this().invoke("count")));

        for (Attribute attribute : amqpFields) {
            if (generator.getMapping().get(attribute.type) != null) {
                if (attribute.attribute.type().isArray()) {

                } else {
                    writeBody().body().block().staticInvoke(cm.ref(generator.getPrimitiveJavaClass().get(attribute.type)), "write").arg(_this().ref(attribute.attribute.name())).arg(ref("out"));
                }

            }

        }
    }

    private void fillInSizeMethod() {
        size().body()._return(invoke("sizeOfConstructor").plus(invoke("sizeOfBody")));

        sizeOfConstructor().body()._return(ref("NUMERIC_ID_SIZE"));

        JMethod sizeOfFields = cls().method(JMod.PRIVATE, cm.LONG, "sizeOfFields");

        /*
        sizeOfBody().body().assign(JExpr.ref("rc"), JExpr.ref("rc").plus(generator.registry().cls().staticInvoke("instance")
                .invoke("sizer")
                .invoke("sizeOfULong").arg(JExpr.ref("NUMERIC_ID"))));
                */

        sizeOfFields.body().decl(cm.LONG, "fieldSize", lit(0L));

        for (Attribute attribute : amqpFields) {

            if (generator.getMapping().get(attribute.type) != null) {
                if (attribute.attribute.type().isArray()) {
                    sizeOfFields.body().assign(ref("fieldSize"), ref("fieldSize").plus(
                            generator.registry().cls().staticInvoke("instance")
                            .invoke("sizer")
                            .invoke("sizeOfArray")
                                .arg(ref(attribute.attribute.name()))));

                } else {
                    sizeOfFields.body().assign(ref("fieldSize"), ref("fieldSize").plus(
                            generator.registry().cls().staticInvoke("instance")
                                    .invoke("sizer")
                                    .invoke("sizeOf" + toJavaClassName(attribute.type))
                                        .arg(ref(attribute.attribute.name()))));
                }
            } else {
                if (attribute.attribute.type().isArray()) {
                    sizeOfFields.body().assign(ref("fieldSize"), ref("fieldSize").plus(
                            generator.registry().cls().staticInvoke("instance")
                                    .invoke("sizer")
                                    .invoke("sizeOfArray")
                                    .arg(ref(attribute.attribute.name()))));
                } else {

                    JConditional conditional = sizeOfFields.body()
                            ._if(ref(attribute.attribute.name()).ne(_null()));

                    conditional._then()
                            .assign(
                                    ref("fieldSize"), ref("fieldSize").plus(
                                    ref(attribute.attribute.name()).invoke("size")));

                    conditional._else()
                            .assign(ref("fieldSize"), ref("fieldSize").plus(lit(1L)));
                }
            }
        }

        sizeOfFields.body()._return(ref("fieldSize"));

        JMethod getListEncoding = cls().method(JMod.PRIVATE, cm.BYTE, "getListEncoding");
        getListEncoding.param(cm.LONG, "fieldSize");

        JMethod getListWidth = cls().method(JMod.PRIVATE, cm.INT, "getListWidth");
        getListWidth.param(cm.BYTE, "formatCode");
        getListWidth.body()._if(ref("formatCode").eq(cm.ref("AMQPList").staticRef("LIST_LIST8_CODE")))._then()._return(cm.ref("AMQPList").staticRef("LIST_LIST8_WIDTH"));
        getListWidth.body()._return(cm.ref("AMQPList").staticRef("LIST_LIST32_WIDTH"));

        getListEncoding.body()
                ._if(ref("fieldSize").lte(lit(255).minus(cm.ref("AMQPList").staticRef("LIST_LIST8_WIDTH"))))
                    ._then()
                        ._return(cm.ref("AMQPList").staticRef("LIST_LIST8_CODE"));

        getListEncoding.body()._return(cm.ref("AMQPList").staticRef("LIST_LIST32_CODE"));


        sizeOfBody().body().decl(cm.LONG, "fieldSize", invoke("sizeOfFields"));
        sizeOfBody().body().decl(cm.LONG, "width", invoke("getListWidth").arg(invoke("getListEncoding").arg(ref("fieldSize"))).mul(lit(2)));
        sizeOfBody().body()._return(lit(1).plus(ref("width").plus(ref("fieldSize"))));
    }

    public JMethod count() {
        return count;
    }
}
