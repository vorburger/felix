/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.schematizer.impl;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.felix.schematizer.Node;
import org.apache.felix.schematizer.Node.CollectionType;
import org.apache.felix.schematizer.Schema;
import org.apache.felix.schematizer.Schematizer;
import org.apache.felix.schematizer.TypeRule;
import org.osgi.dto.DTO;
import org.osgi.service.converter.TypeReference;

public class SchematizerImpl implements Schematizer {

    private final Map<String, SchemaImpl> schemas = new HashMap<>();
    private volatile Map<String, Map<String, Object>> typeRules = new HashMap<>();

    @Override
    public Optional<Schema> get(String name) {
        if (!schemas.containsKey(name)) {
            SchemaImpl schema = schematize(name, "");
            schemas.put(name, schema);
        }

        return Optional.ofNullable(schemas.get(name));
    }

    @Override
    public Optional<Schema> from(String name, Map<String, Node.DTO> map) {
        try {
            // TODO: some validation of the Map here would be good
            SchemaImpl schema = new SchemaImpl(name);
            Node.DTO rootDTO = map.get("/");
            Map<String, NodeImpl> allNodes = new HashMap<>();
            NodeImpl root = new NodeImpl(rootDTO, "", instantiator, allNodes);
            schema.add(root);
            schema.add(allNodes);
            return Optional.of(schema);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public <T extends DTO> Schematizer rule(String name, TypeRule<T> rule) {
        Map<String, Object> rules = rulesFor(name);
        rules.put(rule.getPath(), rule.getType());
        return this;
    }

    @Override
    public <T extends DTO> Schematizer rule(String name, String path, TypeReference<T> type) {
        Map<String, Object> rules = rulesFor(name);
        rules.put(path, type);
        return this;
    }

    @Override
    public <T extends DTO> Schematizer rule(String name, TypeReference<T> type) {
        Map<String, Object> rules = rulesFor(name);
        rules.put("/", type);
        return this;
    }

    @Override
    public <T> Schematizer rule(String name, String path, Class<T> cls) {
        Map<String, Object> rules = rulesFor(name);
        rules.put(path, cls);
        return this;
    }

    private Map<String, Object> rulesFor(String name) {
        if (!typeRules.containsKey(name))
            typeRules.put(name, new HashMap<>());

        return typeRules.get(name);
    }

    /**
     * Top-level entry point for schematizing a DTO. This is the starting point to set up the
     * parsing. All other methods make recursive calls.
     */
    private SchemaImpl schematize(String name, String contextPath) {
        Map<String, Object> rules = typeRules.get(name);
        rules = ( rules != null ) ? rules : Collections.emptyMap();
        return SchematizerImpl.internalSchematize(name, contextPath, rules, false, this);
    }

    @SuppressWarnings( { "unchecked", "rawtypes" } )
    /**
     * Schematize any node, without knowing in advance its type.
     */
    private static SchemaImpl internalSchematize(
            String name, 
            String contextPath, 
            Map<String, Object> rules,
            boolean isCollection,
            SchematizerImpl schematizer)  {
        Class<?> cls = null;
        TypeReference<? extends DTO> ref = null;
        if (contextPath.isEmpty() && rules.containsKey("/")) {
            ref = (TypeReference)typeReferenceOf(rules.get("/"));
            if (ref == null )
                cls = rawClassOf(rules.get("/"));
        }

        if (rules.containsKey(contextPath)) {
            ref = (TypeReference)(typeReferenceOf(rules.get(contextPath)));
            if (ref == null )
                cls = rawClassOf(rules.get(contextPath));
        }

        if (ref != null )
            cls = rawClassOf(ref);

        if (cls == null)
            return handleInvalid();

        if (DTO.class.isAssignableFrom(cls)) {
            Class<? extends DTO> targetCls = (Class<DTO>)cls;
            return schematizeDTO(name, targetCls, ref, contextPath, rules, isCollection, schematizer);            
        }

        return schematizeObject( name, cls, contextPath, rules, isCollection, schematizer);
    }

    private static SchemaImpl schematizeDTO(
            String name, 
            Class<? extends DTO> targetCls, 
            TypeReference<? extends DTO> ref, 
            String contextPath,
            Map<String, Object> rules,
            boolean isCollection,
            SchematizerImpl schematizer) {

        SchemaImpl schema = new SchemaImpl(name);
        NodeImpl rootNode;
        if (ref != null)
            rootNode = new NodeImpl(contextPath, ref, false, contextPath + "/");
        else
            rootNode = new NodeImpl(contextPath, targetCls, false, contextPath + "/");
        schema.add(rootNode);
        Map<String, NodeImpl> m = createMapFromDTO(name, targetCls, ref, contextPath, rules, schematizer);
        m.values().stream().forEach(n -> n.parent(rootNode));
        m.values().stream().filter(v -> v.absolutePath().equals(rootNode.absolutePath() + v.name())).forEach(v -> rootNode.add(v));
        schema.add(m);
        return schema;
    }

    private static SchemaImpl schematizeObject(
            String name, 
            Class<?> targetCls,
            String contextPath,
            Map<String, Object> rules,
            boolean isCollection,
            SchematizerImpl schematizer) {

        SchemaImpl schema = new SchemaImpl(name);
        NodeImpl node = new NodeImpl(contextPath, targetCls, isCollection, contextPath + "/");
        schema.add(node);
//        Map<String, NodeImpl> m = createMapFromDTO(name, targetCls, ref, contextPath, rules, schematizer);
//        m.values().stream().forEach(n -> n.parent(node));
//        m.values().stream().filter(v -> v.absolutePath().equals(node.absolutePath() + v.name())).forEach(v -> node.add(v));
//        schema.add(m);
        return schema;
    }

    private static final Comparator<Entry<String, NodeImpl>> byPath = (e1, e2) -> e1.getValue().absolutePath().compareTo(e2.getValue().absolutePath());
    private static Map<String, NodeImpl> createMapFromDTO(
            String name,
            Class<?> targetCls, 
            TypeReference<? extends DTO> ref, 
            String contextPath, 
            Map<String, Object> typeRules,
            SchematizerImpl schematizer) {
        Set<String> handledFields = new HashSet<>();

        Map<String, NodeImpl> result = new HashMap<>();
        for (Field f : targetCls.getDeclaredFields()) {
            handleField(name, f, handledFields, result, targetCls, ref, contextPath, typeRules, schematizer);
        }
        for (Field f : targetCls.getFields()) {
            handleField(name, f, handledFields, result, targetCls, ref, contextPath, typeRules, schematizer);
        }

        return result.entrySet().stream().sorted(byPath).collect(Collectors.toMap(Entry::getKey, Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    @SuppressWarnings( { "rawtypes", "unchecked" } )
    private static void handleField(
            String name,
            Field field, 
            Set<String> handledFields, 
            Map<String, NodeImpl> result, 
            Class<?> targetCls, 
            TypeReference<?> ref, 
            String contextPath,
            Map<String, Object> rules,
            SchematizerImpl schematizer) {
        if (Modifier.isStatic(field.getModifiers()))
            return;

        String fieldName = field.getName();
        if (handledFields.contains(fieldName))
            return; // Field with this name was already handled

        try {
            String path = contextPath + "/" + fieldName;
            NodeImpl node;
            if (rules.containsKey(path)) {
                // The actual field. Since the type for this node is provided as a rule, we 
                // only need it to test whether or not it is a collection.
                Class<?> actualFieldType = field.getType();
                boolean isCollection = Collection.class.isAssignableFrom(actualFieldType);
                Class<?> rawClass = rawClassOf(rules.get(path));
                // This is the type we will persist in the Schema (as provided by the rules), NOT the "actual" field type.
                SchemaImpl embedded = SchematizerImpl.internalSchematize(name, path, rules, isCollection, schematizer);
                Class<?> fieldClass = Util.primitiveToBoxed(rawClass);
                TypeReference fieldRef = typeReferenceOf(rules.get(path));
                if (isCollection)
                    node = new CollectionNode(
                            field.getName(), 
                            fieldRef, 
                            path,
                            (Class)actualFieldType);
                else if (fieldRef != null )
                    node = new NodeImpl(fieldName, fieldRef, false, path);
                else
                    node = new NodeImpl(fieldName, fieldClass, false, path);
                Map<String, NodeImpl> allNodes = embedded.toMapInternal();
                allNodes.remove(path + "/");
                result.putAll(allNodes);
                Map<String, NodeImpl> childNodes = new HashMap<>();
                allNodes.keySet().stream()
                    .forEach( k -> {String k2 = k.replace(path, ""); childNodes.put(k2, allNodes.get(k));} );
                node.add(childNodes);
            } else {
                Type fieldType = field.getType();
                Class<?> rawClass = rawClassOf(fieldType);
                Class<?> fieldClass = Util.primitiveToBoxed(rawClass);

                if (Collection.class.isAssignableFrom(fieldClass)) {
                    CollectionType collectionTypeAnnotation = field.getAnnotation( CollectionType.class );
                    Class<?> collectionType;
                    if (collectionTypeAnnotation != null)
                        collectionType = collectionTypeAnnotation.value();
                    else
                        collectionType = Object.class;
                    node = new CollectionNode(
                            field.getName(), 
                            collectionType, 
                            path,
                            (Class)fieldClass);

                    if (DTO.class.isAssignableFrom(collectionType)) {
                        SchematizerImpl newSchematizer = new SchematizerImpl();
                        newSchematizer.typeRules.put(path, rules);
                        if (!rules.containsKey(path))
                            newSchematizer.rule(path, path, collectionType);
                        SchemaImpl embedded = newSchematizer.schematize(path, path);
                        Map<String, NodeImpl> allNodes = embedded.toMapInternal();
                        allNodes.remove(path + "/");
                        result.putAll(allNodes);
                        Map<String, NodeImpl> childNodes = new HashMap<>();
                        allNodes.keySet().stream()
                            .forEach( k -> {String k2 = k.replace(path, ""); childNodes.put(k2, allNodes.get(k));} );
                        node.add(childNodes);
                    }
                }
                else if (DTO.class.isAssignableFrom(fieldClass)) {
                    SchematizerImpl newSchematizer = new SchematizerImpl();
                    newSchematizer.typeRules.put(path, rules);
                    if (!rules.containsKey(path))
                        newSchematizer.rule(path, path, fieldClass);
                    SchemaImpl embedded = newSchematizer.schematize(path, path);
                    node = new NodeImpl(
                            field.getName(), 
                            fieldClass,
                            false,
                            path);
                    Map<String, NodeImpl> allNodes = embedded.toMapInternal();
                    allNodes.remove(path + "/");
                    result.putAll(allNodes);
                    Map<String, NodeImpl> childNodes = new HashMap<>();
                    allNodes.keySet().stream()
                        .forEach( k -> {String k2 = k.replace(path, ""); childNodes.put(k2, allNodes.get(k));} );
                    node.add(childNodes);
                } else {
                    node = new NodeImpl(
                            field.getName(), 
                            fieldClass, 
                            false, 
                            path);
                }
            }

            result.put(node.absolutePath(), node);
            handledFields.add(fieldName);
        } catch (Exception e) {
            // Ignore this field
            // TODO print warning??
            return;
        }
    }

    private static SchemaImpl handleInvalid() {
        // TODO
        return null;
    }

    private static Class<?> rawClassOf(Object type) {
        Class<?> rawClass = null;
        if (type instanceof Class) {
            rawClass = (Class<?>)type;
        } else if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type rawType = paramType.getRawType();
            if (rawType instanceof Class)
                rawClass = (Class<?>)rawType;
        } else if (type instanceof TypeReference) {
            return rawClassOf(((TypeReference<?>)type).getType());
        }

        return rawClass;
    }

    private static TypeReference<?> typeReferenceOf(Object type) {
        TypeReference<?> typeRef = null;
        if (type instanceof TypeReference)
            typeRef = (TypeReference<?>)type;
        return typeRef;
    }

    /**
     * In an OSGi environment, this is too naive, and will quickly break down.
     * Consider it more as a placeholder for now.
     */
    private static final Instantiator instantiator = new Instantiator();
    public static class Instantiator implements Function<String, Type> {
        @Override
        public Type apply(String className) {
            try {
                return Class.forName(className);
            } catch ( ClassNotFoundException e ) {
                return Object.class;
            }
        }
    }
}
