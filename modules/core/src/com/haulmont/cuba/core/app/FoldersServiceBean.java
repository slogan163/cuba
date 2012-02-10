/*
 * Copyright (c) 2009 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Konstantin Krivopustov
 * Created: 10.12.2009 17:41:44
 *
 * $Id$
 */
package com.haulmont.cuba.core.app;

import com.haulmont.cuba.core.*;
import com.haulmont.cuba.core.entity.AppFolder;
import com.haulmont.cuba.core.entity.Folder;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.security.entity.SearchFolder;
import groovy.lang.Binding;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service(FoldersService.NAME)
public class FoldersServiceBean implements FoldersService {

    private Log log = LogFactory.getLog(FoldersServiceBean.class);

    @Inject
    private UserSessionSource userSessionSource;

    public List<AppFolder> loadAppFolders() {
        log.debug("Loading AppFolders");

        List<AppFolder> result = new ArrayList<AppFolder>();

        Transaction tx = Locator.createTransaction();
        try {
            EntityManager em = PersistenceProvider.getEntityManager();
            Class<AppFolder> replacedClass = MetadataProvider.getReplacedClass(AppFolder.class);
            Query q = em.createQuery("select f from " + MetadataProvider.getSession().getClass(replacedClass).getName()
                    +" f order by f.sortOrder, f.name");
            List<AppFolder> list = q.getResultList();

            if (!list.isEmpty()) {
                Binding binding = new Binding();
                for (AppFolder folder : list) {
                    try {
                        if (!StringUtils.isBlank(folder.getVisibilityScript())) {
                            binding.setVariable("folder", folder);
                            Boolean visible = runScript(folder.getVisibilityScript(), binding);
                            if (BooleanUtils.isFalse(visible))
                                continue;
                        }
                        if (!StringUtils.isBlank(folder.getQuantityScript())) {
                            String variable = "style";
                            binding.setVariable("folder", folder);
                            binding.setVariable(variable, null);
                            Number qty = runScript(folder.getQuantityScript(), binding);
                            folder.setItemStyle((String) binding.getVariable(variable));
                            folder.setQuantity(qty == null ? null : qty.intValue());
                        }
                    } catch (Exception e) {
                        log.warn("Unable to evaluate AppFolder scripts", e);
                        //continue;
                    }

                    folder.getParent(); // fetch parent
                    result.add(folder);
                }
            }

            tx.commit();
            return result;
        } finally {
            tx.end();
        }
    }

    private <T> T runScript(String script, Binding binding) {
        Object result;
        script = StringUtils.trim(script);
        if (script.endsWith(".groovy")) {
            script = ScriptingProvider.getResourceAsString(script);
        }
        result = ScriptingProvider.evaluateGroovy(script, binding);
        return (T) result;
    }

    public List<AppFolder> reloadAppFolders(List<AppFolder> folders) {
        log.debug("Reloading AppFolders " + folders);

        Transaction tx = Locator.createTransaction();
        try {
            if (!folders.isEmpty()) {
                Binding binding = new Binding();
                for (AppFolder folder : folders) {
                    try {
                        if (!StringUtils.isBlank(folder.getQuantityScript())) {
                            String variable = "style";
                            binding.setVariable("folder", folder);
                            binding.setVariable(variable, null);
                            Number qty = runScript(folder.getQuantityScript(), binding);
                            folder.setItemStyle((String) binding.getVariable(variable));
                            folder.setQuantity(qty == null ? null : qty.intValue());
                        }
                    } catch (Exception e) {
                        log.warn("Unable to evaluate AppFolder scripts", e);
                    }
                }
            }

            tx.commit();
            return folders;
        } finally {
            tx.end();
        }
    }

    public List<SearchFolder> loadSearchFolders() {
        log.debug("Loading SearchFolders");

        Transaction tx = Locator.createTransaction();
        try {
            EntityManager em = PersistenceProvider.getEntityManager();
            Query q = em.createQuery("select f from sec$SearchFolder f " +
                    "left join fetch f.user " +
                    "left join fetch f.presentation " +
                    "where (f.user.id = ?1 or f.user is null) " +
                    "order by f.sortOrder, f.name");
            q.setParameter(1, userSessionSource.currentOrSubstitutedUserId());
            List<SearchFolder> list = q.getResultList();
            // fetch parents
            for (SearchFolder folder : list) {
                folder.getParent();
            }

            tx.commit();
            return list;
        } finally {
            tx.end();
        }
    }

    @Override
    public byte[] exportFolder(Folder folder) throws IOException {
        return FolderHelper.exportFolder(folder);
    }

    @Override
    public Folder importFolder(Folder parentFolder, byte[] bytes) throws IOException {
        return FolderHelper.importFolder(parentFolder, bytes);
    }
}
