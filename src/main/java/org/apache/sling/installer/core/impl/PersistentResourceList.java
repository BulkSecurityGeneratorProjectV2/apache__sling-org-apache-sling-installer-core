/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.installer.core.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.sling.installer.api.InstallableResource;
import org.apache.sling.installer.api.tasks.RegisteredResource;
import org.apache.sling.installer.api.tasks.TaskResource;
import org.apache.sling.installer.api.tasks.TransformationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent list of RegisteredResource, used by installer to
 * keep track of all registered resources
 */
public class PersistentResourceList {

    /** Serialization version. */
    private static final int VERSION = 2;

    /** The logger */
    private final Logger logger =  LoggerFactory.getLogger(this.getClass());

    /**
     * Map of registered resource sets.
     * The key of the map is the entity id of the registered resource.
     * The value is a set containing all registered resources for the
     * same entity. Usually this is just one resource per entity.
     */
    private final Map<String, EntityResourceList> data;

    /** The persistence file. */
    private final File dataFile;

    /** All untransformed resources. */
    private final List<RegisteredResource> untransformedResources;

    @SuppressWarnings("unchecked")
    public PersistentResourceList(final File dataFile) {
        this.dataFile = dataFile;

        Map<String, EntityResourceList> restoredData = null;
        List<RegisteredResource> unknownList = null;
        if ( dataFile.exists() ) {
            ObjectInputStream ois = null;
            try {
                ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(dataFile)));
                final int version = ois.readInt();
                if ( version > 0 && version <= VERSION ) {
                    restoredData = (Map<String, EntityResourceList>)ois.readObject();
                    if ( version == VERSION ) {
                        unknownList = (List<RegisteredResource>)ois.readObject();
                    }
                } else {
                    logger.warn("Unknown version for persistent resource list: {}", version);
                }
                logger.debug("Restored resource list: {}", restoredData);
                logger.debug("Restored unknown resource list: {}", unknownList);
            } catch (final Exception e) {
                logger.warn("Unable to restore data, starting with empty list (" + e.getMessage() + ")", e);
            } finally {
                if (ois != null) {
                    try {
                        ois.close();
                    } catch (final IOException ignore) {
                        // ignore
                    }
                }
            }
        }
        data = restoredData != null ? restoredData : new HashMap<String, EntityResourceList>();
        this.untransformedResources = unknownList != null ? unknownList : new ArrayList<RegisteredResource>();

        this.updateCache();
    }

    /**
     * Update the url to digest cache
     */
    private void updateCache() {
        for(final EntityResourceList group : this.data.values()) {
            for(final RegisteredResource rr : group.getResources()) {
                try {
                    if ( rr.getInputStream() != null ) {
                        FileDataStore.SHARED.updateDigestCache(rr.getURL(), rr.getDigest());
                    }
                } catch (final IOException ioe) {
                    // we just ignore this
                }
            }
        }
    }

    /**
     * Persist the current state
     */
    public void save() {
        try {
            final ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(dataFile)));
            try {
                oos.writeInt(VERSION);
                oos.writeObject(data);
                oos.writeObject(untransformedResources);
                logger.debug("Persisted resource list.");
            } finally {
                oos.close();
            }
        } catch (final Exception e) {
            logger.warn("Unable to save persistent list: " + e.getMessage(), e);
        }
    }

    public Collection<String> getEntityIds() {
        return this.data.keySet();
    }

    /**
     * Add or update an installable resource.
     * @param input The installable resource
     */
    public void addOrUpdate(final InternalResource input) {
        boolean found = false;
        // first check if there are resources with the same url and digest
        for(final EntityResourceList group : this.data.values()) {
            for(final RegisteredResource rr : group.getResources()) {
                if ( rr.getURL().equals(input.getURL()) && ( rr.getDigest().equals(input.getDigest()))) {
                    found = true;
                    break;
                }
            }
            if ( found ) {
                break;
            }
        }
        // if we found the resource we can immediately return
        if ( found ) {
            return;
        }
        try {
            final TaskResource registeredResource = RegisteredResourceImpl.create(input);
            this.checkInstallable(registeredResource);
        } catch (final IOException ioe) {
            logger.warn("Ignoring resource. Error during processing of " + input.getURL(), ioe);
        }
    }

    /**
     * Check if the provided installable resource is already installable (has a
     * known resource type)
     */
    private void checkInstallable(final TaskResource input) {
        if ( !InstallableResource.TYPE_FILE.equals(input.getType())
             && !InstallableResource.TYPE_PROPERTIES.equals(input.getType()) ) {

            EntityResourceList t = this.data.get(input.getEntityId());
            if (t == null) {
                t = new EntityResourceList();
                this.data.put(input.getEntityId(), t);
            }

            t.addOrUpdate(input);
        } else {
            this.untransformedResources.add(input);
        }
    }

    /**
     * Get the list of untransformed resources = resources without resource type
     */
    public List<RegisteredResource> getUntransformedResources() {
        return this.untransformedResources;
    }

    /**
     * Remove a resource by url
     * @param url The url to remove
     */
    public void remove(final String url) {
        for(final EntityResourceList group : this.data.values()) {
            group.remove(url);
        }
    }

    /**
     * Remove a resource.
     */
    public void remove(final TaskResource r) {
        final EntityResourceList group = this.data.get(r.getEntityId());
        if ( group != null ) {
            group.remove(r);
        }
    }

    /**
     * Get the resource group for an entity id.
     */
    public EntityResourceList getEntityResourceList(final String entityId) {
        return this.data.get(entityId);
    }

    /**
     * Compact the internal state and remove empty groups.
     */
    public boolean compact() {
        boolean changed = false;
        final Iterator<Map.Entry<String, EntityResourceList>> i = this.data.entrySet().iterator();
        while ( i.hasNext() ) {
            final Map.Entry<String, EntityResourceList> entry = i.next();

            changed |= entry.getValue().compact();
            if ( entry.getValue().isEmpty() ) {
                changed = true;
                i.remove();
            }
        }
        return changed;
    }

    /**
     * Transform an unknown resource to a registered one
     */
    public void transform(final RegisteredResource resource,
                          final TransformationResult[] result) {
        // remove resource from unknown list
        this.untransformedResources.remove(resource);
        try {
            for(int i=0; i<result.length; i++) {
                final TaskResource clone =  ((RegisteredResourceImpl)resource).clone(result[i]);
                this.checkInstallable(clone);
            }
        } catch (final IOException ioe) {
            logger.warn("Ignoring resource. Error during processing of " + resource, ioe);
        }
    }

}
