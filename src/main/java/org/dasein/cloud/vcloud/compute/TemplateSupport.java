/**
 * Copyright (C) 2009-2013 enStratus Networks Inc
 *
 * ====================================================================
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
 * ====================================================================
 */

package org.dasein.cloud.vcloud.compute;

import org.apache.log4j.Logger;
import org.dasein.cloud.AsynchronousTask;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.AbstractImageSupport;
import org.dasein.cloud.compute.Architecture;
import org.dasein.cloud.compute.ImageClass;
import org.dasein.cloud.compute.ImageCreateOptions;
import org.dasein.cloud.compute.ImageFilterOptions;
import org.dasein.cloud.compute.MachineImage;
import org.dasein.cloud.compute.MachineImageFormat;
import org.dasein.cloud.compute.MachineImageState;
import org.dasein.cloud.compute.MachineImageType;
import org.dasein.cloud.compute.Platform;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.compute.VmState;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.cloud.vcloud.vCloud;
import org.dasein.cloud.vcloud.vCloudMethod;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.uom.time.Minute;
import org.dasein.util.uom.time.TimePeriod;
import org.dasein.util.uom.time.Week;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements vApp Template support in accordance with the Dasein Cloud image support model. Dasein Cloud images map
 * to vApp templates in a vCloud catalog.
 * <p>Created by George Reese: 9/17/12 10:58 AM</p>
 * @author George Reese
 * @author Erik Johnson
 * @author Tim Freeman
 * @version 2013.07
 * @since 2013.04
 */
public class TemplateSupport extends AbstractImageSupport {
    static private final Logger logger = vCloud.getLogger(TemplateSupport.class);
    static private final Lock lockCreationLock = new ReentrantLock();

    static public class Catalog {
        public String catalogId;
        public String name;
        public boolean published;
        public String owner;
    }

    public TemplateSupport(@Nonnull vCloud cloud) {
        super(cloud);
    }

    @Override
    protected MachineImage capture(@Nonnull ImageCreateOptions options, @Nullable AsynchronousTask<MachineImage> task) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Image.capture");
        try {
            vCloudMethod method = new vCloudMethod((vCloud)getProvider());
            String vmId = options.getVirtualMachineId();

            if( vmId == null ) {
                throw new CloudException("A capture operation requires a valid VM ID");
            }
            VirtualMachine vm = ((vCloud)getProvider()).getComputeServices().getVirtualMachineSupport().getVirtualMachine(vmId);
            String vAppId = (vm == null ? null : (String)vm.getTag(vAppSupport.PARENT_VAPP_ID));

            if( vm == null ) {
                throw new CloudException("No such virtual machine: " + vmId);
            }
            else if( vAppId == null ) {
                throw new CloudException("Unable to determine virtual machine vApp for capture: " + vmId);
            }
            long timeout = (System.currentTimeMillis() + CalendarWrapper.MINUTE * 10L);

            while( timeout > System.currentTimeMillis() ) {
                if( vm == null ) {
                    throw new CloudException("VM " + vmId + " went away");
                }
                if( !vm.getCurrentState().equals(VmState.PENDING) ) {
                    break;
                }
                try { Thread.sleep(15000L); }
                catch( InterruptedException ignore ) { }
                try { vm = ((vCloud)getProvider()).getComputeServices().getVirtualMachineSupport().getVirtualMachine(vmId); }
                catch( Throwable ignore ) { }
            }
            boolean running = !vm.getCurrentState().equals(VmState.STOPPED);
            String vappId = (String)vm.getTag(vAppSupport.PARENT_VAPP_ID);

            if( running ) {
                ((vCloud)getProvider()).getComputeServices().getVirtualMachineSupport().undeploy(vappId, "shutdown");
            }
            try {
                String endpoint = method.toURL("vApp", vAppId);
                StringBuilder xml = new StringBuilder();

                xml.append("<CaptureVAppParams xmlns=\"http://www.vmware.com/vcloud/v1.5\" xmlns:ovf=\"http://schemas.dmtf.org/ovf/envelope/1\" name=\"").append(vCloud.escapeXml(options.getName())).append("\">");
                xml.append("<Description>").append(options.getDescription()).append("</Description>");
                xml.append("<Source href=\"").append(endpoint).append("\" type=\"").append(method.getMediaTypeForVApp()).append("\"/>");
                xml.append("<CustomizationSection><ovf:Info/><CustomizeOnInstantiate>true</CustomizeOnInstantiate></CustomizationSection>");
                xml.append("</CaptureVAppParams>");

                String response = method.post(vCloudMethod.CAPTURE_VAPP, vm.getProviderDataCenterId(), xml.toString());

                if( response.equals("") ) {
                    throw new CloudException("No error or other information was in the response");
                }
                Document doc = method.parseXML(response);

                try {
                    method.checkError(doc);
                }
                catch( CloudException e ) {
                    if( e.getMessage().contains("Stop the vApp and try again") ) {
                        logger.warn("The cloud thinks the vApp or VM is still running; going to check what's going on: " + e.getMessage());
                        vm = ((vCloud)getProvider()).getComputeServices().getVirtualMachineSupport().getVirtualMachine(vmId);
                        if( vm == null ) {
                            throw new CloudException("Virtual machine went away");
                        }
                        if( !vm.getCurrentState().equals(VmState.STOPPED) ) {
                            logger.warn("Current state of VM: " + vm.getCurrentState());
                            ((vCloud)getProvider()).getComputeServices().getVirtualMachineSupport().undeploy(vappId, "shutdown");
                        }
                        response = method.post(vCloudMethod.CAPTURE_VAPP, vm.getProviderDataCenterId(), xml.toString());
                        if( response.equals("") ) {
                            throw new CloudException("No error or other information was in the response");
                        }
                        doc = method.parseXML(response);
                        method.checkError(doc);
                    }
                    else {
                        throw e;
                    }
                }

                NodeList vapps = doc.getElementsByTagName("VAppTemplate");

                if( vapps.getLength() < 1 ) {
                    throw new CloudException("No vApp templates were found in response");
                }
                Node vapp = vapps.item(0);
                String imageId = null;
                Node href = vapp.getAttributes().getNamedItem("href");

                if( href != null ) {
                    imageId = ((vCloud)getProvider()).toID(href.getNodeValue().trim());
                }
                if( imageId == null || imageId.length() < 1 ) {
                    throw new CloudException("No imageId was found in response");
                }
                MachineImage img = loadVapp(imageId, getContext().getAccountNumber(), false, options.getName(), options.getDescription(), System.currentTimeMillis());

                if( img == null ) {
                    throw new CloudException("Image was lost");
                }
                method.waitFor(response);
                publish(img);
                return img;
            }
            finally {
                if( running ) {
                    ((vCloud)getProvider()).getComputeServices().getVirtualMachineSupport().deploy(vappId);
                }
            }
        }
        finally {
            APITrace.end();
        }
    }

    private void publish(@Nonnull MachineImage img) throws CloudException, InternalException {
        vCloudMethod method = new vCloudMethod((vCloud)getProvider());
        Catalog c = null;

        for( Catalog catalog : listPrivateCatalogs() ) {
            if( catalog.owner.equals(getContext().getAccountNumber()) ) {
                c = catalog;
                if( catalog.name.equals("Standard Catalog") ) {
                    break;
                }
            }
        }
        StringBuilder xml;

        if( c == null ) {
            xml = new StringBuilder();
            xml.append("<AdminCatalog xmlns=\"http://www.vmware.com/vcloud/v1.5\" name=\"Standard Catalog\">");
            xml.append("<Description>Standard catalog for custom vApp templates</Description>");
            xml.append("<IsPublished>false</IsPublished>");
            xml.append("</AdminCatalog>");
            String response = method.post("createCatalog", method.toAdminURL("org", getContext().getRegionId()) + "/catalogs", method.getMediaTypeForActionAddCatalog(), xml.toString());
            String href = null;

            method.waitFor(response);
            if( !response.equals("") ) {
                Document doc = method.parseXML(response);
                String docElementTagName = doc.getDocumentElement().getTagName();
                String nsString = "";
                if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
                NodeList matches = doc.getElementsByTagName(nsString + "AdminCatalog");

                for( int i=0; i<matches.getLength(); i++ ) {
                    Node m = matches.item(i);
                    Node h = m.getAttributes().getNamedItem("href");

                    if( h != null ) {
                        href = h.getNodeValue().trim();
                        break;
                    }
                }
            }
            if( href == null ) {
                throw new CloudException("No catalog could be identified for publishing vApp template " + img.getProviderMachineImageId());
            }
            c = getCatalog(false, href);
            if( c == null ) {
                throw new CloudException("No catalog could be identified for publishing vApp template " + img.getProviderMachineImageId());
            }
        }

        xml = new StringBuilder();
        xml.append("<CatalogItem xmlns=\"http://www.vmware.com/vcloud/v1.5\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
        xml.append("name=\"").append(vCloud.escapeXml(img.getName())).append("\">");
        xml.append("<Description>").append(vCloud.escapeXml(img.getDescription())).append("</Description>");
        xml.append("<Entity href=\"").append(method.toURL("vAppTemplate", img.getProviderMachineImageId())).append("\" ");
        xml.append("name=\"").append(vCloud.escapeXml(img.getName())).append("\" ");
        xml.append("type=\"").append(method.getMediaTypeForVAppTemplate()).append("\" xsi:type=\"").append("ResourceReferenceType\"/>");
        xml.append("</CatalogItem>");

        method.waitFor(method.post("publish", method.toURL("catalog", c.catalogId) + "/catalogItems", method.getMediaTypeForCatalogItem(), xml.toString()));
    }

    private @Nullable Catalog getCatalog(boolean published, @Nonnull String href) throws CloudException, InternalException {
        String catalogId = ((vCloud)getProvider()).toID(href);
        vCloudMethod method = new vCloudMethod((vCloud)getProvider());
        String xml = method.get("catalog", catalogId);

        if( xml == null ) {
            logger.warn("Unable to find catalog " + catalogId + " indicated by org " + getContext().getAccountNumber());
            return null;
        }
        Document doc = method.parseXML(xml);
        String docElementTagName = doc.getDocumentElement().getTagName();
        String nsString = "";
        if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
        NodeList cNodes = doc.getElementsByTagName(nsString + "Catalog");

        for( int i=0; i<cNodes.getLength(); i++ ) {
            Node cnode = cNodes.item(i);

            Node name = cnode.getAttributes().getNamedItem("name");
            String catalogName = null;

            if( name != null ) {
                catalogName = name.getNodeValue().trim();
            }
            if( cnode.hasChildNodes() ) {
                NodeList attributes = cnode.getChildNodes();
                String owner = "--public--";
                boolean p = false;

                for( int j=0; j<attributes.getLength(); j++ ) {
                    Node attribute = attributes.item(j);
                    if(attribute.getNodeName().contains(":"))nsString = attribute.getNodeName().substring(0, attribute.getNodeName().indexOf(":") + 1);
                    else nsString = "";

                    if( attribute.getNodeName().equalsIgnoreCase(nsString + "IsPublished") ) {
                        p = (attribute.hasChildNodes() && attribute.getFirstChild().getNodeValue().trim().equalsIgnoreCase("true"));
                    }
                    else if( attribute.getNodeName().equalsIgnoreCase(nsString + "Link") && attribute.hasAttributes() ) {
                        Node rel = attribute.getAttributes().getNamedItem("rel");

                        if( rel != null && rel.getNodeValue().trim().equalsIgnoreCase("up") ) {
                            Node type = attribute.getAttributes().getNamedItem("type");

                            if( type != null && type.getNodeValue().trim().equalsIgnoreCase(method.getMediaTypeForOrg()) ) {
                                Node h = attribute.getAttributes().getNamedItem("href");

                                if( h != null ) {
                                    owner = method.getOrgName(h.getNodeValue().trim());
                                }
                            }
                        }
                    }
                }
                if( p == published ) {
                    Catalog catalog = new Catalog();
                    catalog.catalogId = ((vCloud)getProvider()).toID(href);
                    catalog.published = p;
                    catalog.owner = owner;
                    catalog.name = catalogName;
                    return catalog;
                }
            }

        }
        return null;
    }

    @Override
    public MachineImage getImage(@Nonnull String providerImageId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Image.getImage");
        try {
            for( MachineImage image : listImages((ImageFilterOptions)null) ) {
                if( image.getProviderMachineImageId().equals(providerImageId) ) {
                    return image;
                }
            }
            for( MachineImage image : searchPublicImages((ImageFilterOptions)null) ) {
                if( image.getProviderMachineImageId().equals(providerImageId) ) {
                    return image;
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String getProviderTermForImage(@Nonnull Locale locale, @Nonnull ImageClass cls) {
        return "vApp Template";
    }

    @Override
    public boolean isImageSharedWithPublic(@Nonnull String machineImageId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Image.isImageSharedWithPublic");
        try {
            MachineImage img = getImage(machineImageId);

            if( img == null ) {
                return false;
            }
            Boolean p = (Boolean)img.getTag("public");

            return (p != null && p);
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Image.isSubscribed");
        try {
            return (getProvider().testContext() != null);
        }
        finally {
            APITrace.end();
        }
    }

    private Iterable<Catalog> listPublicCatalogs() throws CloudException, InternalException {
        Cache<Catalog> cache = Cache.getInstance(getProvider(), "publicCatalogs", Catalog.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Minute>(30, TimePeriod.MINUTE));
        Iterable<Catalog> catalogs = cache.get(getContext());

        if( catalogs == null ) {
            vCloudMethod method = new vCloudMethod((vCloud)getProvider());
            String xml = method.get("org", getContext().getRegionId());

            if( xml == null ) {
                catalogs = Collections.emptyList();
            }
            else {
                ArrayList<Catalog> list = new ArrayList<Catalog>();
                Document doc = method.parseXML(xml);
                String docElementTagName = doc.getDocumentElement().getTagName();
                String nsString = "";
                if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
                NodeList links = doc.getElementsByTagName(nsString + "Link");

                for( int i=0; i<links.getLength(); i++ ) {
                    Node link = links.item(i);

                    if( link.hasAttributes() ) {
                        Node rel = link.getAttributes().getNamedItem("rel");

                        if( rel != null && rel.getNodeValue().trim().equalsIgnoreCase("down") ) {
                            Node type = link.getAttributes().getNamedItem("type");

                            if( type != null && type.getNodeValue().trim().equals(method.getMediaTypeForCatalog()) ) {
                                Node href = link.getAttributes().getNamedItem("href");
                                Catalog c = getCatalog(true, href.getNodeValue().trim());

                                if( c != null ) {
                                    list.add(c);
                                }
                            }
                        }
                    }
                }
                catalogs = list;
            }
            cache.put(getContext(), catalogs);
        }
        return catalogs;
    }

    private Iterable<Catalog> listPrivateCatalogs() throws CloudException, InternalException {
        Cache<Catalog> cache = Cache.getInstance(getProvider(), "privateCatalogs", Catalog.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Minute>(30, TimePeriod.MINUTE));
        Iterable<Catalog> catalogs = cache.get(getContext());

        if( catalogs == null ) {
            vCloudMethod method = new vCloudMethod((vCloud)getProvider());
            String xml = method.get("org", getContext().getRegionId());

            if( xml == null ) {
                catalogs = Collections.emptyList();
            }
            else {
                ArrayList<Catalog> list = new ArrayList<Catalog>();
                Document doc = method.parseXML(xml);
                String docElementTagName = doc.getDocumentElement().getTagName();
                String nsString = "";
                if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
                NodeList links = doc.getElementsByTagName(nsString + "Link");

                for( int i=0; i<links.getLength(); i++ ) {
                    Node link = links.item(i);

                    if( link.hasAttributes() ) {
                        Node rel = link.getAttributes().getNamedItem("rel");

                        if( rel != null && rel.getNodeValue().trim().equalsIgnoreCase("down") ) {
                            Node type = link.getAttributes().getNamedItem("type");

                            if( type != null && type.getNodeValue().trim().equals(method.getMediaTypeForCatalog()) ) {
                                Node href = link.getAttributes().getNamedItem("href");
                                Catalog c = getCatalog(false, href.getNodeValue().trim());

                                if( c != null ) {
                                    list.add(c);
                                }
                            }
                        }
                    }
                }
                catalogs = list;
            }
            cache.put(getContext(), catalogs);
        }
        return catalogs;
    }

    @Override
    public @Nonnull Iterable<MachineImage> listImages(final @Nullable ImageFilterOptions options) throws CloudException, InternalException {
        Cache<MachineImage> cache = Cache.getInstance(getProvider(), "listImages", MachineImage.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Minute>(6, TimePeriod.MINUTE));
        Iterable<MachineImage> imageList = cache.get(getContext());
        if (imageList != null) {
            return imageList;
        }

        // Limit the amount of listImage calls to one-at-a-time per REGION_ACCOUNT with a lock.
        // The call can be very expensive.
        Cache<Lock> lockCache = Cache.getInstance(getProvider(), "listImagesLock", Lock.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Week>(500, TimePeriod.WEEK));
        Iterable<Lock> lockList = lockCache.get(getContext());
        if (lockList == null) {
            lockCreationLock.lock();
            try {
                if (lockList == null) {
                    ArrayList<Lock> oneLockList = new ArrayList<Lock>();
                    oneLockList.add(new ReentrantLock());
                    lockCache.put(getContext(), oneLockList);
                }
                lockList = lockCache.get(getContext());
            } finally {
                lockCreationLock.unlock();
            }
        }
        Lock lock = null;
        for (Lock onelock: lockList) {
            lock = onelock;
            break;
        }
        if (lock == null) {
            throw new InternalException("No lock.");
        }

        APITrace.begin(getProvider(), "Image.listImages");
        lock.lock();
        try {
            Iterable<MachineImage> imageList2 = cache.get(getContext());
            if (imageList2 != null) {
                // A thread we were waiting on has refreshed the cache
                return imageList2;
            }

            ArrayList<MachineImage> images = new ArrayList<MachineImage>();

            for( Catalog catalog : listPrivateCatalogs() ) {
                vCloudMethod method = new vCloudMethod((vCloud)getProvider());
                String xml = method.get("catalog", catalog.catalogId);

                if( xml == null ) {
                    logger.warn("Unable to find catalog " + catalog.catalogId + " indicated by org " + getContext().getAccountNumber());
                    continue;
                }
                Document doc = method.parseXML(xml);
                String docElementTagName = doc.getDocumentElement().getTagName();
                String nsString = "";
                if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
                NodeList cNodes = doc.getElementsByTagName(nsString + "Catalog");

                for( int i=0; i<cNodes.getLength(); i++ ) {
                    Node cnode = cNodes.item(i);

                    if( cnode.hasChildNodes() ) {
                        NodeList items = cnode.getChildNodes();

                        for( int j=0; j<items.getLength(); j++ ) {
                            Node wrapper = items.item(j);
                            if(wrapper.getNodeName().contains(":"))nsString = wrapper.getNodeName().substring(0, wrapper.getNodeName().indexOf(":") + 1);
                            else nsString = "";

                            if( wrapper.getNodeName().equalsIgnoreCase(nsString + "CatalogItems") && wrapper.hasChildNodes() ) {
                                NodeList entries = wrapper.getChildNodes();

                                for( int k=0; k<entries.getLength(); k++ ) {
                                    Node item = entries.item(k);
                                    if(item.getNodeName().contains(":"))nsString = item.getNodeName().substring(0, item.getNodeName().indexOf(":") + 1);
                                    else nsString = "";

                                    if( item.getNodeName().equalsIgnoreCase(nsString + "CatalogItem") && item.hasAttributes() ) {
                                        Node href = item.getAttributes().getNamedItem("href");

                                        if( href != null ) {
                                            String catalogItemId = ((vCloud)getProvider()).toID(href.getNodeValue().trim());
                                            MachineImage image = loadTemplate(catalog.owner, catalogItemId, catalog.published);
                                            if( image != null ) {
                                                if( options == null || options.matches(image) ) {
                                                    image.setProviderOwnerId(catalog.owner);
                                                    image.setTag("catalogItemId", catalogItemId);
                                                    images.add(image);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            cache.put(getContext(), images);
            return images;
        }
        finally {
            lock.unlock();
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<MachineImageFormat> listSupportedFormats() throws CloudException, InternalException {
        return Collections.singletonList(MachineImageFormat.VMDK);
    }

    @Override
    public @Nonnull Iterable<String> listShares(@Nonnull String forMachineImageId) throws CloudException, InternalException {
        return Collections.emptyList();
    }

    private @Nullable MachineImage loadTemplate(@Nonnull String ownerId, @Nonnull String catalogItemId, boolean published) throws CloudException, InternalException {
        vCloudMethod method = new vCloudMethod((vCloud)getProvider());
        String xml = method.get("catalogItem", catalogItemId);

        if( xml == null ) {
            logger.warn("Catalog item " + catalogItemId + " is missing from the catalog");
            return null;
        }
        Document doc = method.parseXML(xml);
        String docElementTagName = doc.getDocumentElement().getTagName();
        String nsString = "";
        if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
        NodeList items = doc.getElementsByTagName(nsString + "CatalogItem");

        if( items.getLength() < 1 ) {
            return null;
        }
        Node item = items.item(0);

        if( item.hasAttributes() && item.hasChildNodes() ) {
            Node name = item.getAttributes().getNamedItem("name");
            String imageName = null, imageDescription = null;
            long createdAt = 0L;

            if( name != null ) {
                String n = name.getNodeValue().trim();

                if( n.length() > 0 ) {
                    imageName = n;
                    imageDescription = n;
                }
            }
            NodeList entries = item.getChildNodes();
            String vappId = null;

            for( int i=0; i<entries.getLength(); i++ ) {
                Node entry = entries.item(i);

                if( entry.getNodeName().equalsIgnoreCase("description") && entry.hasChildNodes() ) {
                    String d = entry.getFirstChild().getNodeValue().trim();

                    if( d.length() > 0 ) {
                        imageDescription = d;
                        if( imageName == null ) {
                            imageName = d;
                        }
                    }
                }
                else if( entry.getNodeName().equalsIgnoreCase("datecreated") && entry.hasChildNodes() ) {
                    createdAt = ((vCloud)getProvider()).parseTime(entry.getFirstChild().getNodeValue().trim());
                }
                else if( entry.getNodeName().equalsIgnoreCase(nsString + "entity") && entry.hasAttributes() ) {
                    Node href = entry.getAttributes().getNamedItem("href");

                    if( href != null ) {
                        vappId = ((vCloud)getProvider()).toID(href.getNodeValue().trim());
                    }
                }
            }
            if( vappId != null ) {
                return loadVapp(vappId, ownerId, published, imageName, imageDescription, createdAt);
            }
        }
        return null;
    }
    //imageId, options.getName(), options.getDescription(), System.currentTimeMillis()
    private @Nullable MachineImage loadVapp(@Nonnull String imageId, @Nonnull String ownerId, boolean published, @Nullable String name, @Nullable String description, @Nonnegative long createdAt) throws CloudException, InternalException {
        vCloudMethod method = new vCloudMethod((vCloud)getProvider());

        String xml = method.get("vAppTemplate", imageId);

        if( xml == null ) {
            return null;
        }
        Document doc = method.parseXML(xml);
        String docElementTagName = doc.getDocumentElement().getTagName();
        String nsString = "";
        if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
        NodeList templates = doc.getElementsByTagName(nsString + "VAppTemplate");

        if( templates.getLength() < 1 ) {
            return null;
        }
        Node template = templates.item(0);
        TreeSet<String> childVms = new TreeSet<String>();

        if( name == null ) {
            Node node = template.getAttributes().getNamedItem("name");

            if( node != null ) {
                String n = node.getNodeValue().trim();

                if( n.length() > 0 ) {
                    name = n;
                }
            }
        }
        NodeList attributes = template.getChildNodes();
        Platform platform = Platform.UNKNOWN;
        Architecture architecture = Architecture.I64;
        TagPair tagPair = null;
        String parentNetworkHref = null, parentNetworkId = null, parentNetworkName = null, networkConf = null;

        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);

            if(attribute.getNodeName().contains(":")){
                nsString = attribute.getNodeName().substring(0, attribute.getNodeName().indexOf(":") + 1);
            }
            else{
                nsString="";
            }

            if( attribute.getNodeName().equalsIgnoreCase(nsString + "description") && description == null && attribute.hasChildNodes() ) {
                String d = attribute.getFirstChild().getNodeValue().trim();

                if( d.length() > 0 ) {
                    description = d;
                    if( name == null ) {
                        name = d;
                    }
                }
            }
            // need network config details
            else if ( attribute.getNodeName().equalsIgnoreCase(nsString + "networkconfigsection") && attribute.hasChildNodes()) {
                NodeList networkConfigs = attribute.getChildNodes();

                for (int item=0; item<networkConfigs.getLength(); item++) {
                    Node networkConfig = networkConfigs.item(item);

                    if (networkConfig.getNodeName().equalsIgnoreCase(nsString + "networkconfig") && networkConfig.hasChildNodes()) {
                        StringWriter sw = new StringWriter();
                        try {
                            Transformer t = TransformerFactory.newInstance().newTransformer();
                            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                            t.setOutputProperty(OutputKeys.INDENT, "yes");
                            t.transform(new DOMSource(networkConfig), new StreamResult(sw));
                        } catch (TransformerException te) {
                            System.out.println("nodeToString Transformer Exception");
                        }
                        networkConf = sw.toString();

                        NodeList configs = networkConfig.getChildNodes();

                        for (int configItem=0; configItem<configs.getLength(); configItem++) {
                            Node config = configs.item(configItem);

                            if (config.getNodeName().equalsIgnoreCase(nsString + "configuration") && config.hasChildNodes()) {
                                NodeList c = config.getChildNodes();

                                for (int conf = 0; conf<c.getLength(); conf++) {
                                    Node conf2 = c.item(conf);

                                    if (conf2.getNodeName().equalsIgnoreCase(nsString + "parentnetwork") && conf2.hasAttributes()) {
                                        Node parentHref = conf2.getAttributes().getNamedItem("href");
                                        Node parentId = conf2.getAttributes().getNamedItem("id");
                                        Node parentName = conf2.getAttributes().getNamedItem("name");
                                        if (parentHref != null) {
                                            parentNetworkHref = parentHref.getNodeValue().trim();
                                        }
                                        if (parentId != null) {
                                            parentNetworkId = parentId.getNodeValue().trim();
                                        }
                                        if (parentHref != null) {
                                            parentNetworkName = parentName.getNodeValue().trim();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else if( attribute.getNodeName().equalsIgnoreCase(nsString + "children") && attribute.hasChildNodes() ) {
                NodeList children = attribute.getChildNodes();

                for( int j=0; j<children.getLength(); j++ ) {
                    Node child = children.item(j);

                    if( child.getNodeName().equalsIgnoreCase(nsString + "vm") && child.hasChildNodes() ) {
                        Node childHref = child.getAttributes().getNamedItem("href");

                        if( childHref != null ) {
                            childVms.add(((vCloud)getProvider()).toID(childHref.getNodeValue().trim()));
                        }
                        NodeList vmAttrs = child.getChildNodes();

                        for( int k=0; k<vmAttrs.getLength(); k++ ) {
                            Node vmAttr = vmAttrs.item(k);
                            if(vmAttr.getNodeName().contains(":"))nsString = vmAttr.getNodeName().substring(0, vmAttr.getNodeName().indexOf(":") + 1);

                            if( vmAttr.getNodeName().equalsIgnoreCase(nsString + "guestcustomizationsection") && vmAttr.hasChildNodes() ) {
                                NodeList custList = vmAttr.getChildNodes();

                                for( int l=0; l<custList.getLength(); l++ ) {
                                    Node cust = custList.item(l);

                                    if( cust.getNodeName().equalsIgnoreCase(nsString + "computername") && cust.hasChildNodes() ) {
                                        String n = cust.getFirstChild().getNodeValue().trim();

                                        if( n.length() > 0 ) {
                                            if( name == null ) {
                                                name = n;
                                            }
                                            else {
                                                name = name + " - " + n;
                                            }
                                        }
                                    }
                                }
                            }
                            else if( vmAttr.getNodeName().equalsIgnoreCase(nsString + "ProductSection") && vmAttr.hasChildNodes() ) {
                                NodeList prdList = vmAttr.getChildNodes();

                                for( int l=0; l<prdList.getLength(); l++ ) {
                                    Node prd = prdList.item(l);

                                    if( prd.getNodeName().equalsIgnoreCase(nsString + "Product") && prd.hasChildNodes() ) {
                                        String n = prd.getFirstChild().getNodeValue().trim();

                                        if( n.length() > 0 ) {
                                            platform = Platform.guess(n);
                                        }
                                    }
                                }
                            }
                            else if( vmAttr.getNodeName().equalsIgnoreCase(nsString + "OperatingSystemSection") && vmAttr.hasChildNodes() ) {
                                NodeList os = vmAttr.getChildNodes();

                                for( int l=0; l<os.getLength(); l++ ) {
                                    Node osdesc = os.item(l);

                                    if( osdesc.getNodeName().equalsIgnoreCase(nsString + "Description") && osdesc.hasChildNodes() ) {
                                        String desc = osdesc.getFirstChild().getNodeValue();

                                        platform = Platform.guess(desc);

                                        if( desc.contains("32") || (desc.contains("x86") && !desc.contains("64")) ) {
                                            architecture = Architecture.I32;
                                        }
                                    }
                                }
                            } else if (vmAttr.getNodeName().equalsIgnoreCase((nsString + "NetworkConnectionSection")) && vmAttr.hasChildNodes()) {
                                tagPair = parseNetworkConnectionSection(vmAttr, nsString);
                            }
                        }
                    }
                }
            }
            else if( attribute.getNodeName().equalsIgnoreCase(nsString + "datecreated") && attribute.hasChildNodes() ) {
                createdAt = ((vCloud)getProvider()).parseTime(attribute.getFirstChild().getNodeValue().trim());
            }
            else if (attribute.getNodeName().equalsIgnoreCase(nsString + "LeaseSettingsSection") && attribute.hasChildNodes()){
                if (logger.isTraceEnabled()){
                    logger.trace("Checking lease settings for VAppTemplate : " +  name);
                }
                NodeList children = attribute.getChildNodes();
                for( int j=0; j<children.getLength(); j++ ) {
                    Node child = children.item(j);
                    if( child.getNodeName().equalsIgnoreCase(nsString + "StorageLeaseExpiration") && child.hasChildNodes() ) {
                        String expiryDateString = child.getFirstChild().getNodeValue().trim();
                        Date expiryDate = new Date(vCloud.parseTime(expiryDateString));
                        if (expiryDate != null){
                            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                            if (cal.getTimeInMillis() > expiryDate.getTime()){
                                if (logger.isTraceEnabled()){
                                    logger.trace("vAppTemplate " + name + " has an expired storage lease.");
                                }
                                return null;
                            }
                        }
                    }
                }

            }
        }
        if( name == null ) {
            name = imageId;
        }
        if( description == null ) {
            description = name;
        }
        if( platform.equals(Platform.UNKNOWN) ) {
            platform = Platform.guess(name + " " + description);
        }
        MachineImage image = MachineImage.getMachineImageInstance(ownerId, getContext().getRegionId(), imageId, MachineImageState.ACTIVE, name, description, architecture, platform).createdAt(createdAt);
        StringBuilder ids = new StringBuilder();

        for( String id : childVms ) {
            if( ids.length() > 0 ) {
                ids.append(",");
            }
            ids.append(id);
        }
        image.setTag("childVirtualMachineIds", ids.toString());
        if( published ) {
            image.setTag("public", "true");
        }
        if (tagPair != null) {
            if (tagPair.defaultVlanName != null) {
                image.setTag("defaultVlanName", tagPair.defaultVlanName);
            }
            if (tagPair.defaultVlanNameDHCP != null) {
                image.setTag("defaultVlanNameDHCP", tagPair.defaultVlanNameDHCP);
            }
        }
        image.setTag("parentNetworkHref", parentNetworkHref);
        image.setTag("parentNetworkId", parentNetworkId);
        image.setTag("parentNetworkName", parentNetworkName);
        image.setTag("fullNetConf", networkConf);
        return image;
    }

    private class TagPair {
        String defaultVlanName;
        String defaultVlanNameDHCP;
        private TagPair(String defaultVlanName, String defaultVlanNameDHCP) {
            this.defaultVlanName = nonEmpty(defaultVlanName);
            this.defaultVlanNameDHCP = nonEmpty(defaultVlanNameDHCP);
        }
        private String nonEmpty(String input) {
            if (input == null) {
                return null;
            } else if (input.trim().isEmpty()) {
                return null;
            } else {
                return input.trim();
            }
        }
    }

    private TagPair parseNetworkConnectionSection(@Nonnull Node vmAttr, @Nonnull String nsString) {
        int primaryNetIndex = -1;
        NodeList netList = vmAttr.getChildNodes();
        for ( int i=0; i<netList.getLength(); i++ ) {
            Node node = netList.item(i);
            if (node.getNodeName().equalsIgnoreCase(nsString + "PrimaryNetworkConnectionIndex")) {
                primaryNetIndex = Integer.parseInt(node.getFirstChild().getNodeValue().trim());
                break;
            }
        }
        String defaultVlanName =  null;
        String defaultVlanNameDHCP = null;
        if (primaryNetIndex >= 0) {
            for ( int i=0; i<netList.getLength(); i++ ) {
                Node node = netList.item(i);
                if (node.getNodeName().equalsIgnoreCase(nsString + "NetworkConnection")) {
                    NodeList netNodeChildren = node.getChildNodes();
                    for ( int j=0; j<netNodeChildren.getLength(); j++ ) {
                        Node netNodeChild = netNodeChildren.item(j);
                        if (netNodeChild.getNodeName().equalsIgnoreCase(nsString + "NetworkConnectionIndex")) {
                            int thisIndex = Integer.parseInt(netNodeChild.getFirstChild().getNodeValue().trim());
                            if (primaryNetIndex == thisIndex) {
                                NamedNodeMap netNodeChildAttributes = node.getAttributes();
                                Node networkNode = netNodeChildAttributes.getNamedItem("network");
                                String networkName = networkNode.getNodeValue();
                                for ( int k=0; k<netNodeChildren.getLength(); k++ ) {
                                    Node netNodeChild2 = netNodeChildren.item(k);
                                    if (netNodeChild2.getNodeName().equalsIgnoreCase(nsString + "IpAddressAllocationMode")) {
                                        if ("DHCP".equalsIgnoreCase(netNodeChild2.getFirstChild().getNodeValue().trim())) {
                                            defaultVlanNameDHCP = networkName;
                                        } else {
                                            defaultVlanName = networkName;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return new TagPair(defaultVlanName, defaultVlanNameDHCP);
    }

    @Override
    public void remove(@Nonnull String providerImageId, boolean checkState) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Image.remove");
        try {
            MachineImage image = getImage(providerImageId);

            if( image == null ) {
                throw new CloudException("No such image: " + providerImageId);
            }
            vCloudMethod method = new vCloudMethod((vCloud)getProvider());
            String catalogItemId = (String)image.getTag("catalogItemId");

            method.delete("vAppTemplate", providerImageId);
            if( catalogItemId != null ) {
                method.delete("catalogItem", catalogItemId);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<MachineImage> searchPublicImages(@Nonnull ImageFilterOptions options) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "Image.searchPublicImages");
        try {
            ArrayList<MachineImage> images = new ArrayList<MachineImage>();

            for( Catalog catalog : listPublicCatalogs() ) {
                vCloudMethod method = new vCloudMethod((vCloud)getProvider());
                String xml = method.get("catalog", catalog.catalogId);

                if( xml == null ) {
                    logger.warn("Unable to find catalog " + catalog.catalogId + " indicated by org " + getContext().getAccountNumber());
                    continue;
                }
                Document doc = method.parseXML(xml);
                String docElementTagName = doc.getDocumentElement().getTagName();
                String nsString = "";
                if(docElementTagName.contains(":"))nsString = docElementTagName.substring(0, docElementTagName.indexOf(":") + 1);
                NodeList cNodes = doc.getElementsByTagName(nsString + "Catalog");

                for( int i=0; i<cNodes.getLength(); i++ ) {
                    Node cnode = cNodes.item(i);

                    if( cnode.hasChildNodes() ) {
                        NodeList items = cnode.getChildNodes();

                        for( int j=0; j<items.getLength(); j++ ) {
                            Node wrapper = items.item(j);
                            if(wrapper.getNodeName().contains(":"))nsString = wrapper.getNodeName().substring(0, wrapper.getNodeName().indexOf(":") + 1);
                            else nsString = "";

                            if( wrapper.getNodeName().equalsIgnoreCase(nsString + "CatalogItems") && wrapper.hasChildNodes() ) {
                                NodeList entries = wrapper.getChildNodes();

                                for( int k=0; k<entries.getLength(); k++ ) {
                                    Node item = entries.item(k);
                                    if(item.getNodeName().contains(":"))nsString = item.getNodeName().substring(0, item.getNodeName().indexOf(":") + 1);
                                    else nsString = "";

                                    if( item.getNodeName().equalsIgnoreCase(nsString + "CatalogItem") && item.hasAttributes() ) {
                                        Node href = item.getAttributes().getNamedItem("href");

                                        if( href != null ) {
                                            String catalogItemId = ((vCloud)getProvider()).toID(href.getNodeValue().trim());
                                            MachineImage image = loadTemplate(catalog.owner, catalogItemId, catalog.published);

                                            if( image != null ) {
                                                if( options == null || options.matches(image) ) {
                                                    image.setProviderOwnerId(catalog.owner);
                                                    image.setTag("catalogItemId", catalogItemId);
                                                    images.add(image);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                }
            }
            return images;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean supportsCustomImages() {
        return true;
    }

    @Override
    public boolean supportsImageCapture(@Nonnull MachineImageType type) {
        return type.equals(MachineImageType.VOLUME);
    }

    @Override
    public boolean supportsPublicLibrary(@Nonnull ImageClass cls) {
        return cls.equals(ImageClass.MACHINE);
    }
}
