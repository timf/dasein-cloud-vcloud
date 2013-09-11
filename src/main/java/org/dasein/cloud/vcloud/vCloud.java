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

package org.dasein.cloud.vcloud;

import org.apache.log4j.Logger;
import org.dasein.cloud.AbstractCloud;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.vcloud.compute.vCloudComputeServices;
import org.dasein.cloud.vcloud.network.vCloudNetworkServices;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Calendar;
import java.util.Properties;

/**
 * Bootstrapping class for interacting with vCloud Director through Dasein Cloud. This implementation is a complete
 * re-architecture from scratch with options for backwards compatibility with the old jclouds-based implementation of
 * Dasein Cloud for vCloud.
 * @author George Reese
 * @since 2013.04
 * @version 2013.04 initial version
 */
public class vCloud extends AbstractCloud {
    static private final Logger logger = getLogger(vCloud.class);

    static private @Nonnull String getLastItem(@Nonnull String name) {
        int idx = name.lastIndexOf('.');

        if( idx < 0 ) {
            return name;
        }
        else if( idx == (name.length()-1) ) {
            return "";
        }
        return name.substring(idx+1);
    }

    static public Logger getLogger(Class<?> cls) {
        String pkg = getLastItem(cls.getPackage().getName());

        if( pkg.equals("aws") ) {
            pkg = "";
        }
        else {
            pkg = pkg + ".";
        }
        return Logger.getLogger("dasein.cloud.vcloud.std." + pkg + getLastItem(cls.getName()));
    }

    static public Logger getWireLogger(Class<?> cls) {
        return Logger.getLogger("dasein.cloud.vcloud.wire." + getLastItem(cls.getPackage().getName()) + "." + getLastItem(cls.getName()));
    }

    static public String escapeXml(String nonxml) {
        StringBuilder str = new StringBuilder();

        for( int i=0; i<nonxml.length(); i++ ) {
            char c = nonxml.charAt(i);

            switch( c ) {
                case '&': str.append("&amp;"); break;
                case '>': str.append("&gt;"); break;
                case '<': str.append("&lt;"); break;
                case '"': str.append("&quot;"); break;
                case '[': str.append("&#091;"); break;
                case ']': str.append("&#093;"); break;
                case '!': str.append("&#033;"); break;
                default: str.append(c);
            }
        }
        return str.toString();
    }

    public vCloud() { }

    @Override
    public @Nonnull String getCloudName() {
        ProviderContext ctx = getContext();
        String name = (ctx == null ? null : ctx.getCloudName());

        return (name == null ? "Private vCloud Cloud" : name);
    }

    @Override
    public @Nonnull vCloudComputeServices getComputeServices() {
        return new vCloudComputeServices(this);
    }

    @Override
    public @Nonnull VDCServices getDataCenterServices() {
        return new VDCServices(this);
    }

    @Override
    public @Nonnull vCloudNetworkServices getNetworkServices() {
        return new vCloudNetworkServices(this);
    }

    @Override
    public @Nonnull String getProviderName() {
        ProviderContext ctx = getContext();
        String name = (ctx == null ? null : ctx.getProviderName());

        return (name == null ? "VMware" : name);
    }

    public @Nullable String[] getVersionPreference() {
        ProviderContext ctx = getContext();
        String value;

        if( ctx == null ) {
            value = null;
        }
        else {
            Properties p = ctx.getCustomProperties();

            if( p == null ) {
                value = null;
            }
            else {
                value = p.getProperty("versionPreference");
            }
        }
        if( value == null ) {
            value = System.getProperty("vCloudVersionPreference");
        }
        if( value == null ) {
            return null;
        }
        else if( value.contains(",") ) {
            return value.trim().split(",");
        }
        else {
            return new String[] { value };
        }
    }


    public @Nonnull String getVMProductsResource() {
        ProviderContext ctx = getContext();
        String value;

        if( ctx == null ) {
            value = null;
        }
        else {
            Properties p = ctx.getCustomProperties();

            if( p == null ) {
                value = null;
            }
            else {
                value = p.getProperty("vmproducts");
            }
        }
        if( value == null ) {
            value = System.getProperty("vcloud.vmproducts");
        }
        if( value == null ) {
            value = "/org/dasein/cloud/vcloud/vmproducts.json";
        }
        return value;
    }

    public boolean isCompat() {
        ProviderContext ctx = getContext();
        String value;

        if( ctx == null ) {
            value = null;
        }
        else {
            Properties p = ctx.getCustomProperties();

            if( p == null ) {
                value = null;
            }
            else {
                value = p.getProperty("compat");
            }
        }
        if( value == null ) {
            value = System.getProperty("vCloudCompat");
        }
        return (value != null && value.equalsIgnoreCase("true"));
    }

    public boolean isInsecure() {
        ProviderContext ctx = getContext();
        String value;

        if( ctx == null ) {
            value = null;
        }
        else {
            Properties p = ctx.getCustomProperties();

            if( p == null ) {
                value = null;
            }
            else {
                value = p.getProperty("insecure");
            }
        }
        if( value == null ) {
            value = System.getProperty("insecure");
        }
        return (value != null && value.equalsIgnoreCase("true"));
    }

    @Override
    public String testContext() {
        APITrace.begin(this, "testContext");
        try {
            ProviderContext ctx = getContext();

            if( ctx == null ) {
                logger.warn("No context exists for testing");
                return null;
            }
            try {
                vCloudMethod method = new vCloudMethod(this);

                method.authenticate(true);
                return ctx.getAccountNumber();
            }
            catch( Throwable t ) {
                logger.warn("Unable to connect to " + getCloudName() + " for " + ctx.getAccountNumber() + ": " + t.getMessage());
                return null;
            }
        }
        finally {
            APITrace.end();
        }
    }

    public @Nonnull String toID(@Nonnull String url) {
        String[] parts = url.split("/");

        if( parts.length > 2 ) {
            if( isCompat() ) {
                return "/" + parts[parts.length-2] + "/" + parts[parts.length-1];
            }
            else {
                return parts[parts.length-1];
            }
        }
        return url;
    }

    @Override
    public @Nonnull String toString() {
        ProviderContext ctx = getContext();

        return (getProviderName() + " - " + getCloudName() + (ctx == null ? "" : " [" + ctx.getAccountNumber() + "]"));
    }

    // Path for non-static
    public @Nonnegative long parseTime(@Nullable String time) throws CloudException {
        return parseIsoDate(time);
    }

    /**
     * @param isoDateString ISO 8601 string
     * @return unix timestamp in ms, or zero
     * @throws CloudException
     */
    public static long parseIsoDate(@Nullable String isoDateString) throws CloudException {
        if (isoDateString == null || isoDateString.trim().isEmpty()) {
            throw new CloudException("Received empty timestamp");
        }
        final Calendar cal = ISO8601.parse(isoDateString);
        if (cal == null) {
            return 0L;
        }
        return cal.getTimeInMillis();
	}
}
