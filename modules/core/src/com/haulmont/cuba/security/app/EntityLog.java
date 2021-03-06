/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.haulmont.cuba.security.app;

import com.haulmont.bali.util.Preconditions;
import com.haulmont.chile.core.datatypes.Datatypes;
import com.haulmont.chile.core.model.Instance;
import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.chile.core.model.MetaProperty;
import com.haulmont.chile.core.model.Range;
import com.haulmont.cuba.core.*;
import com.haulmont.cuba.core.app.ServerConfig;
import com.haulmont.cuba.core.app.dynamicattributes.DynamicAttributes;
import com.haulmont.cuba.core.app.dynamicattributes.DynamicAttributesUtils;
import com.haulmont.cuba.core.entity.*;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.EntityManagerContext;
import com.haulmont.cuba.core.sys.persistence.EntityAttributeChanges;
import com.haulmont.cuba.security.entity.*;
import org.apache.commons.lang.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Component(EntityLogAPI.NAME)
public class EntityLog implements EntityLogAPI {

    private final Logger log = LoggerFactory.getLogger(EntityLog.class);

    @Inject
    protected TimeSource timeSource;
    @Inject
    protected Persistence persistence;
    @Inject
    protected Metadata metadata;
    @Inject
    protected MetadataTools metadataTools;
    @Inject
    protected UserSessionSource userSessionSource;
    @Inject
    protected ReferenceToEntitySupport referenceToEntitySupport;
    @Inject
    protected DynamicAttributes dynamicAttributes;
    @Inject
    protected DataManager dataManager;
    @Inject
    protected ServerConfig serverConfig;

    protected volatile boolean loaded;
    protected EntityLogConfig config;

    @GuardedBy("lock")
    protected Map<String, Set<String>> entitiesManual;
    @GuardedBy("lock")
    protected Map<String, Set<String>> entitiesAuto;

    protected ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    protected ThreadLocal<Boolean> entityLogSwitchedOn = new ThreadLocal<>();

    @Inject
    public EntityLog(Configuration configuration) {
        config = configuration.getConfig(EntityLogConfig.class);
    }

    @Override
    public void processLoggingForCurrentThread(boolean enabled) {
        entityLogSwitchedOn.set(enabled);
    }

    @Override
    public boolean isLoggingForCurrentThread() {
        return !Boolean.FALSE.equals(entityLogSwitchedOn.get());
    }

    @Override
    public void flush() {
        EntityManagerContext context = persistence.getEntityManagerContext();
        List<EntityLogItem> items = context.getAttribute(EntityLog.class.getName());
        if (items == null || items.isEmpty())
            return;

        for (EntityLogItem item : items) {
            List<EntityLogItem> sameEntityList = items.stream()
                    .filter(entityLogItem -> entityLogItem.getObjectEntityId().equals(item.getObjectEntityId()))
                    .collect(Collectors.toList());
            EntityLogItem itemToSave = sameEntityList.get(0);
            computeChanges(itemToSave, sameEntityList);
            saveItem(itemToSave);
        }
    }

    private void computeChanges(EntityLogItem itemToSave, List<EntityLogItem> sameEntityList) {
        Set<String> allAttributes = sameEntityList.stream()
                .flatMap(entityLogItem -> entityLogItem.getAttributes().stream().map(EntityLogAttr::getName))
                .collect(Collectors.toSet());

        for (String attributeName : allAttributes) {
            // old value from the first item
            sameEntityList.get(0).getAttributes().stream()
                    .filter(entityLogAttr -> entityLogAttr.getName().equals(attributeName))
                    .findFirst()
                    .ifPresent(entityLogAttr -> setAttributeOldValue(entityLogAttr, itemToSave));
            // new value from the last item
            sameEntityList.get(sameEntityList.size() - 1).getAttributes().stream()
                    .filter(entityLogAttr -> entityLogAttr.getName().equals(attributeName))
                    .findFirst()
                    .ifPresent(entityLogAttr -> setAttributeNewValue(entityLogAttr, itemToSave));
        }

        Properties properties = new Properties();

        for (EntityLogAttr attr : itemToSave.getAttributes()) {
            properties.setProperty(attr.getName(), attr.getValue());
            if (attr.getValueId() != null) {
                properties.setProperty(attr.getName() + EntityLogAttr.VALUE_ID_SUFFIX, attr.getValueId());
            }
            if (attr.getOldValue() != null) {
                properties.setProperty(attr.getName() + EntityLogAttr.OLD_VALUE_SUFFIX, attr.getOldValue());
            }
            if (attr.getOldValueId() != null) {
                properties.setProperty(attr.getName() + EntityLogAttr.OLD_VALUE_ID_SUFFIX, attr.getOldValueId());
            }
            if (attr.getMessagesPack() != null) {
                properties.setProperty(attr.getName() + EntityLogAttr.MP_SUFFIX, attr.getMessagesPack() );
            }
        }

        if (itemToSave.getType() == EntityLogItem.Type.MODIFY) {
            sameEntityList.stream()
                    .filter(entityLogItem -> entityLogItem.getType() == EntityLogItem.Type.CREATE)
                    .findFirst()
                    .ifPresent(entityLogItem -> itemToSave.setType(EntityLogItem.Type.CREATE));
        }
        itemToSave.setChanges(getChanges(properties));
    }

    private void setAttributeOldValue(EntityLogAttr entityLogAttr, EntityLogItem itemToSave) {
        EntityLogAttr attr = getAttrToSave(entityLogAttr, itemToSave);
        attr.setOldValue(entityLogAttr.getOldValue());
        attr.setOldValueId(entityLogAttr.getOldValueId());
    }

    private void setAttributeNewValue(EntityLogAttr entityLogAttr, EntityLogItem itemToSave) {
        EntityLogAttr attr = getAttrToSave(entityLogAttr, itemToSave);
        attr.setValue(entityLogAttr.getValue());
        attr.setValueId(entityLogAttr.getValueId());
    }

    private EntityLogAttr getAttrToSave(EntityLogAttr entityLogAttr, EntityLogItem itemToSave) {
        EntityLogAttr attr = itemToSave.getAttributes().stream()
                .filter(a -> a.getName().equals(entityLogAttr.getName()))
                .findFirst()
                .orElse(null);
        if (attr == null) {
            attr = metadata.create(EntityLogAttr.class);
            attr.setName(entityLogAttr.getName());
            itemToSave.getAttributes().add(attr);
        }
        return attr;
    }

    private void saveItem(EntityLogItem item) {
        String storeName = metadataTools.getStoreName(metadata.getClassNN(item.getEntity()));
        if (Stores.isMain(storeName)) {
            EntityManager em = persistence.getEntityManager();
            em.persist(item);
        } else {
            // Create a new transaction in main DB if we are saving an entity from additional data store
            try (Transaction tx = persistence.createTransaction()) {
                EntityManager em = persistence.getEntityManager();
                em.persist(item);
                tx.commit();
            }
        }
    }

    @Override
    public synchronized boolean isEnabled() {
        return config.getEnabled() && isLoggingForCurrentThread();
    }

    @Override
    public synchronized void setEnabled(boolean enabled) {
        if (enabled != config.getEnabled()) {
            config.setEnabled(enabled);
        }
    }

    @Override
    public void invalidateCache() {
        lock.writeLock().lock();
        try {
            log.debug("Invalidating cache");
            entitiesManual = null;
            entitiesAuto = null;
            loaded = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected Set<String> getLoggedAttributes(String entity, boolean auto) {
        lock.readLock().lock();
        try {
            if (!loaded) {
                // upgrade lock
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    if (!loaded) { // recheck because we unlocked for a while
                        loadEntities();
                        loaded = true;
                    }
                } finally {
                    // downgrade lock
                    lock.writeLock().unlock();
                    lock.readLock().lock();
                }
            }

            Set<String> attributes;
            if (auto)
                attributes = entitiesAuto.get(entity);
            else
                attributes = entitiesManual.get(entity);

            return attributes == null ? null : Collections.unmodifiableSet(attributes);
        } finally {
            lock.readLock().unlock();
        }
    }

    protected void loadEntities() {
        log.debug("Loading entities");
        entitiesManual = new HashMap<>();
        entitiesAuto = new HashMap<>();
        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            TypedQuery<LoggedEntity> q = em.createQuery(
                    "select e from sec$LoggedEntity e where e.auto = true or e.manual = true",
                    LoggedEntity.class);
//            q.setView(null);
            List<LoggedEntity> list = q.getResultList();
            for (LoggedEntity loggedEntity : list) {
                if (loggedEntity.getName() == null) {
                    throw new IllegalStateException("Unable to initialize EntityLog: empty LoggedEntity.name");
                }
                Set<String> attributes = new HashSet<>();
                for (LoggedAttribute loggedAttribute : loggedEntity.getAttributes()) {
                    if (loggedAttribute.getName() == null) {
                        throw new IllegalStateException("Unable to initialize EntityLog: empty LoggedAttribute.name");
                    }
                    attributes.add(loggedAttribute.getName());
                }
                if (BooleanUtils.isTrue(loggedEntity.getAuto()))
                    entitiesAuto.put(loggedEntity.getName(), attributes);
                if (BooleanUtils.isTrue(loggedEntity.getManual()))
                    entitiesManual.put(loggedEntity.getName(), attributes);
            }
            tx.commit();
        } finally {
            tx.end();
        }
        log.debug("Loaded: entitiesAuto={}, entitiesManual={}", entitiesAuto.size(), entitiesManual.size());
    }

    protected String getEntityName(Entity entity) {
        MetaClass metaClass;
        if (entity instanceof CategoryAttributeValue) {
            CategoryAttribute categoryAttribute = ((CategoryAttributeValue) entity).getCategoryAttribute();
            Preconditions.checkNotNullArgument(categoryAttribute,"Category attribute is null");
            metaClass = metadata.getClassNN(categoryAttribute.getCategoryEntityType());
        } else {
            metaClass = metadata.getSession().getClassNN(entity.getClass());
        }
        return metadata.getExtendedEntities().getOriginalOrThisMetaClass(metaClass).getName();
    }

    protected boolean doNotRegister(Entity entity) {
        if (entity == null) {
            return true;
        }
        if (entity instanceof EntityLogItem) {
            return true;
        }
        if (metadata.getTools().hasCompositePrimaryKey(entity.getMetaClass()) && !(entity instanceof HasUuid)) {
            return true;
        }
        return !isEnabled();
    }

    @Override
    public void registerCreate(Entity entity) {
        if (entity == null)
            return;
        registerCreate(entity, false);
    }

    @Override
    public void registerCreate(Entity entity, boolean auto) {
        try {
            if (doNotRegister(entity))
                return;
            String masterEntityName = getEntityName(entity);
            boolean isCategoryAttributeValue = entity instanceof CategoryAttributeValue;

            Set<String> attributes = getLoggedAttributes(masterEntityName, auto);
            if (attributes != null && attributes.contains("*")) {
                attributes = getAllAttributes(entity);
            }
            if (attributes == null) {
                return;
            }

            MetaClass metaClass = metadata.getClassNN(masterEntityName);
            attributes = filterRemovedAttributes(metaClass, attributes);

            if (isCategoryAttributeValue) {
                internalRegisterModifyAttributeValue((CategoryAttributeValue) entity, null, attributes);
            } else {
                String storeName = metadata.getTools().getStoreName(metaClass);
                if (Stores.isMain(storeName)) {
                    internalRegisterCreate(entity, masterEntityName, attributes);
                } else {
                    // Create a new transaction in main DB if we are saving an entity from additional data store
                    try (Transaction tx = persistence.createTransaction()) {
                        internalRegisterCreate(entity, masterEntityName, attributes);
                        tx.commit();
                    }
                }
            }
        } catch (Exception e) {
            logError(entity, e);
        }
    }

    protected Set<String> filterRemovedAttributes(MetaClass metaClass, Set<String> attributes) {
        // filter attributes that do not exists in entity anymore
        return attributes.stream()
                .filter(attributeName -> {
                    if (DynamicAttributesUtils.isDynamicAttribute(attributeName)){
                        return DynamicAttributesUtils.getMetaPropertyPath(metaClass, attributeName) != null;
                    } else {
                        return metaClass.getProperty(attributeName) != null;
                    }
                })
                .collect(Collectors.toSet());
    }

    protected void internalRegisterCreate(Entity entity, String entityName, Set<String> attributes) throws IOException {
        Date ts = timeSource.currentTimestamp();
        EntityManager em = persistence.getEntityManager();

        EntityLogItem item = metadata.create(EntityLogItem.class);
        item.setEventTs(ts);
        item.setUser(findUser(em));
        item.setType(EntityLogItem.Type.CREATE);
        item.setEntity(entityName);
        item.setObjectEntityId(referenceToEntitySupport.getReferenceId(entity));
        item.setAttributes(createLogAttributes(entity, attributes, null));

        enqueueItem(item);
    }

    protected void internalRegisterModifyAttributeValue(CategoryAttributeValue entity, @Nullable EntityAttributeChanges changes,
                                                        Set<String> attributes) {
        String propertyName = DynamicAttributesUtils.encodeAttributeCode(entity.getCode());
        if (!attributes.contains(propertyName)) {
            return;
        }

        Date ts = timeSource.currentTimestamp();
        EntityManager em = persistence.getEntityManager();

        Set<String> dirty;
        if (changes == null) {
            dirty = persistence.getTools().getDirtyFields(entity);
        } else {
            dirty = changes.getAttributes();
        }
        boolean registerDeleteOp = dirty.contains("deleteTs") && entity.isDeleted();
        boolean hasChanges = dirty.stream().anyMatch(s -> s.endsWith("Value"));
        if (hasChanges) {
            EntityLogItem item = metadata.create(EntityLogItem.class);
            item.setEventTs(ts);
            item.setUser(findUser(em));
            item.setType(EntityLogItem.Type.MODIFY);
            item.setEntity(getEntityName(entity));
            item.setObjectEntityId(entity.getObjectEntityId());
            item.setAttributes(createDynamicLogAttribute(entity, changes, registerDeleteOp));

            enqueueItem(item);
        }
    }

    protected User findUser(EntityManager em) {
        if (AppContext.isStarted())
            return em.getReference(User.class, userSessionSource.getUserSession().getUser().getId());
        else {
            String login = serverConfig.getJmxUserLogin();
            TypedQuery<User> query = em.createQuery("select u from sec$User u where u.loginLowerCase = ?1", User.class);
            query.setParameter(1, login);
            User user = query.getFirstResult();
            if (user != null)
                return user;
            else
                throw new RuntimeException("The user '" + login + "' specified in cuba.jmxUserLogin does not exist");
        }
    }

    private void enqueueItem(EntityLogItem item) {
        EntityManagerContext context = persistence.getEntityManagerContext();
        List<EntityLogItem> items = context.getAttribute(EntityLog.class.getName());
        if (items == null) {
            items = new ArrayList<>();
            context.setAttribute(EntityLog.class.getName(), items);
        }
        items.add(item);
    }

    @Override
    public void registerModify(Entity entity) {
        registerModify(entity, false);
    }

    @Override
    public void registerModify(Entity entity, boolean auto) {
        registerModify(entity, auto, null);
    }

    @Override
    public void registerModify(Entity entity, boolean auto, @Nullable EntityAttributeChanges changes) {
        try {
            if (doNotRegister(entity))
                return;

            String masterEntityName = getEntityName(entity);
            boolean isCategoryAttributeValue = entity instanceof CategoryAttributeValue;
            Set<String> attributes = getLoggedAttributes(masterEntityName, auto);
            if (attributes != null && attributes.contains("*")) {
                attributes = getAllAttributes(entity);
            }
            if (attributes == null) {
                return;
            }

            MetaClass metaClass = metadata.getClassNN(masterEntityName);
            attributes = filterRemovedAttributes(metaClass, attributes);

            if (isCategoryAttributeValue) {
                internalRegisterModifyAttributeValue((CategoryAttributeValue) entity, changes, attributes);
            } else {
                String storeName = metadataTools.getStoreName(metaClass);
                if (Stores.isMain(storeName)) {
                    internalRegisterModify(entity, changes, metaClass, storeName, attributes);
                } else {
                    // Create a new transaction in main DB if we are saving an entity from additional data store
                    try (Transaction tx = persistence.createTransaction()) {
                        internalRegisterModify(entity, changes, metaClass, storeName, attributes);
                        tx.commit();
                    }
                }
            }
        } catch (Exception e) {
            logError(entity, e);
        }
    }

    protected void internalRegisterModify(Entity entity, @Nullable EntityAttributeChanges changes, MetaClass metaClass,
                                          String storeName, Set<String> attributes) throws IOException {
        Date ts = timeSource.currentTimestamp();
        EntityManager em = persistence.getEntityManager();

        Set<String> dirty;
        if (changes == null) {
            dirty = persistence.getTools().getDirtyFields(entity);
        } else {
            dirty = changes.getAttributes();
        }

        Set<EntityLogAttr> entityLogAttrs;
        EntityLogItem.Type type;
        if (entity instanceof SoftDelete && dirty.contains("deleteTs") && !((SoftDelete) entity).isDeleted()) {
            type = EntityLogItem.Type.RESTORE;
            entityLogAttrs = createLogAttributes(entity, attributes, changes);
        } else {
            type = EntityLogItem.Type.MODIFY;
            Set<String> dirtyAttributes = new HashSet<>();
            for (String attr : attributes) {
                if (dirty.contains(attr)) {
                    dirtyAttributes.add(attr);
                } else if (!Stores.getAdditional().isEmpty()) {
                    String idAttr = metadataTools.getCrossDataStoreReferenceIdProperty(storeName, metaClass.getPropertyNN(attr));
                    if (idAttr != null && dirty.contains(idAttr)) {
                        dirtyAttributes.add(attr);
                    }
                }
            }
            entityLogAttrs = createLogAttributes(entity, dirtyAttributes, changes);
        }
        if (!entityLogAttrs.isEmpty() || type == EntityLogItem.Type.RESTORE) {
            EntityLogItem item = metadata.create(EntityLogItem.class);
            item.setEventTs(ts);
            item.setUser(findUser(em));
            item.setType(type);
            item.setEntity(metaClass.getName());
            item.setObjectEntityId(referenceToEntitySupport.getReferenceId(entity));
            item.setAttributes(entityLogAttrs);

            enqueueItem(item);
        }
    }

    protected Set<EntityLogAttr> createLogAttributes(Entity entity, Set<String> attributes,
                                                   @Nullable EntityAttributeChanges changes) {
        Set<EntityLogAttr> result = new HashSet<>();
        for (String name : attributes) {
            if (DynamicAttributesUtils.isDynamicAttribute(name)) {
                continue;
            }
            EntityLogAttr attr = metadata.create(EntityLogAttr.class);
            attr.setName(name);

            String value = stringify(entity.getValue(name));
            attr.setValue(value);

            Object valueId = getValueId(value);
            if (valueId != null)
                attr.setValueId(valueId.toString());

            if (changes != null) {
                Object oldValue = changes.getOldValue(name);
                attr.setOldValue(stringify(oldValue));
                Object oldValueId = getValueId(oldValue);
                if (oldValueId != null) {
                    attr.setOldValueId(oldValueId.toString());
                }
            }

            MessageTools messageTools = AppBeans.get(MessageTools.NAME);
            String mp = messageTools.inferMessagePack(name, entity);
            if (mp != null)
                attr.setMessagesPack(mp);

            result.add(attr);
        }
        return result;
    }

    protected Set<EntityLogAttr> createDynamicLogAttribute(CategoryAttributeValue entity, @Nullable EntityAttributeChanges changes, boolean registerDeleteOp) {
        Set<EntityLogAttr> result = new HashSet<>();
        EntityLogAttr attr = metadata.create(EntityLogAttr.class);
        attr.setName(DynamicAttributesUtils.encodeAttributeCode(entity.getCode()));

        Object value = entity.getValue();
        attr.setValue(stringify(value));

        Object valueId = getValueId(value);
        if (valueId != null)
            attr.setValueId(valueId.toString());

        if (changes != null || registerDeleteOp) {
            Object oldValue = getOldCategoryAttributeValue(entity, changes);
            attr.setOldValue(stringify(oldValue));
            Object oldValueId = getValueId(oldValue);
            if (oldValueId != null) {
                attr.setOldValueId(oldValueId.toString());
            }
        }
        result.add(attr);
        return result;
    }

    protected String getChanges(Properties properties) {
        try {
            StringWriter writer = new StringWriter();
            properties.store(writer, null);
            String changes = writer.toString();
            if (changes.startsWith("#"))
                changes = changes.substring(changes.indexOf("\n") + 1); // cut off comments line
            return changes;
        } catch (IOException e) {
            throw new RuntimeException("Error writing entity log attributes", e);
        }
    }

    @Override
    public void registerDelete(Entity entity) {
        registerDelete(entity, false);
    }

    @Override
    public void registerDelete(Entity entity, boolean auto) {
        try {
            if (doNotRegister(entity))
                return;

            String masterEntityName = getEntityName(entity);
            boolean isCategoryAttributeValue = entity instanceof CategoryAttributeValue;
            Set<String> attributes = getLoggedAttributes(masterEntityName, auto);
            if (attributes != null && attributes.contains("*")) {
                attributes = getAllAttributes(entity);
            }
            if (attributes == null) {
                return;
            }

            MetaClass metaClass = metadata.getClassNN(masterEntityName);
            attributes = filterRemovedAttributes(metaClass, attributes);
            if (isCategoryAttributeValue) {
                internalRegisterModifyAttributeValue((CategoryAttributeValue) entity, null, attributes);
            } else {
                String storeName = metadata.getTools().getStoreName(metaClass);
                if (Stores.isMain(storeName)) {
                    internalRegisterDelete(entity, masterEntityName, attributes);
                } else {
                    // Create a new transaction in main DB if we are saving an entity from additional data store
                    try (Transaction tx = persistence.createTransaction()) {
                        internalRegisterDelete(entity, masterEntityName, attributes);
                        tx.commit();
                    }
                }
            }
        } catch (Exception e) {
            logError(entity, e);
        }
    }

    protected void internalRegisterDelete(Entity entity, String entityName, Set<String> attributes) throws IOException {
        Date ts = timeSource.currentTimestamp();
        EntityManager em = persistence.getEntityManager();

        EntityLogItem item = metadata.create(EntityLogItem.class);
        item.setEventTs(ts);
        item.setUser(findUser(em));
        item.setType(EntityLogItem.Type.DELETE);
        item.setEntity(entityName);
        item.setObjectEntityId(referenceToEntitySupport.getReferenceId(entity));
        item.setAttributes(createLogAttributes(entity, attributes, null));

        enqueueItem(item);
    }

    protected Set<String> getAllAttributes(Entity entity) {
        if (entity == null) {
            return null;
        }
        Set<String> attributes = new HashSet<>();
        MetaClass metaClass = metadata.getClassNN(entity.getClass());
        for (MetaProperty metaProperty : metaClass.getProperties()) {
            Range range = metaProperty.getRange();
            if (range.isClass() && range.getCardinality().isMany()) {
                continue;
            }
            attributes.add(metaProperty.getName());
        }
        Collection<CategoryAttribute> categoryAttributes = dynamicAttributes.getAttributesForMetaClass(metaClass);
        if (categoryAttributes != null) {
            for (CategoryAttribute categoryAttribute : categoryAttributes) {
                if (BooleanUtils.isNotTrue(categoryAttribute.getIsCollection())) {
                    attributes.add(
                            DynamicAttributesUtils.getMetaPropertyPath(metaClass, categoryAttribute).getMetaProperty().getName());
                }
            }
        }
        return attributes;
    }

    protected Object getValueId(Object value) {
        if (value instanceof EmbeddableEntity) {
            return null;
        } else if (value instanceof BaseGenericIdEntity) {
            return referenceToEntitySupport.getReferenceId((Entity) value);
        } else {
            return null;
        }
    }

    protected String stringify(Object value) {
        if (value == null)
            return "";
        else if (value instanceof Instance) {
            return ((Instance) value).getInstanceName();
        } else if (value instanceof Date) {
            return Datatypes.getNN(value.getClass()).format(value);
        } else if (value instanceof Iterable) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (Object obj : (Iterable) value) {
                sb.append(stringify(obj)).append(",");
            }
            if (sb.length() > 1)
                sb.deleteCharAt(sb.length() - 1);
            sb.append("]");
            return sb.toString();
        } else {
            return String.valueOf(value);
        }
    }

    protected Object getOldCategoryAttributeValue(CategoryAttributeValue attributeValue, EntityAttributeChanges changes) {
        CategoryAttribute categoryAttribute = attributeValue.getCategoryAttribute();
        PersistenceTools persistenceTools = persistence.getTools();
        String fieldName = null;
        switch (categoryAttribute.getDataType()) {
            case DATE:
                fieldName = "dateValue";
                break;
            case ENUMERATION:
            case STRING:
                fieldName = "stringValue";
                break;
            case INTEGER:
                fieldName = "intValue";
                break;
            case DOUBLE:
                fieldName = "doubleValue";
                break;
            case BOOLEAN:
                fieldName = "booleanValue";
                break;
        }
        if (fieldName != null) {
            return changes != null ? changes.getOldValue(fieldName) :
                    persistenceTools.getOldValue(attributeValue, fieldName);
        }
        return null;
    }

    protected void logError(Entity entity, Exception e) {
        log.warn("Unable to log entity {}, id={}", entity, entity.getId(), e);
    }
}