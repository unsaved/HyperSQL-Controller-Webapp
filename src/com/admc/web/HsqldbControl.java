/*
 * @(#)HsqldbControl.java
 *
 * Copyright 2011 Axis Data Management Corp.
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


package com.admc.web;

import java.io.IOException;
import java.io.File;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.List;
import java.util.ArrayList;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletContext;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import org.hsqldb.server.Server;
import org.hsqldb.server.ServerProperties;
import org.hsqldb.server.ServerConstants;
import org.hsqldb.server.ServerAcl.AclFormatException;

public class HsqldbControl implements ServletContextListener {
    private Server server;
    private static Pattern sysPropVarPattern =
            Pattern.compile("\\$\\{([^}]+)\\}");
    private List<DataSource> instanceDataSources = new ArrayList<DataSource>();

    public void contextDestroyed(ServletContextEvent event) {
        ServletContext application = event.getServletContext();
        if (server == null) {
            application.log("Skipping server shutdown since never started");
        } else {
            application.log("Shutting down HyperSQL Server");
            server.shutdown();
            server = null;
        }
        int counter = 0;
        Connection c = null;
        for (DataSource ds : instanceDataSources) try {
            ++counter;
            c = ds.getConnection();
            c.createStatement().executeUpdate("SHUTDOWN");
            application.log("Shut down HyperSQL instance #" + counter);
        } catch (Exception e) {
            application.log("Failed to shut down HyperSQL instance #"
                    + counter, e);
        } finally {
            if (c != null) try {
                c.close();
            } catch (SQLException se) {
                application.log("Failed to close connection after done with it",
                        se);
            }
            c = null;
        }
    }

    public void contextInitialized(ServletContextEvent event) {
        int i = 0;
        ServletContext application = event.getServletContext();
        String inPropPath = application.getInitParameter(
                "hsqldb.server.propertiespath");
        try {
            InitialContext iContext = new InitialContext();
            while (true) try {
                instanceDataSources.add((DataSource) iContext.lookup(
                        "java:/comp/env/hsqldbDsAutostop/" + ++i));
            } catch (NameNotFoundException ne) {
                break;
            }
            application.log(instanceDataSources.size()
                    + " data sources registered for auto-shutdown");
            if (inPropPath == null) {
                application.log(
                    "Not starting HyperSQL Listener, since no "
                    + "'hsqldb.server.propertiespath' init property is set");
                return;
            }
            String s = inPropPath.trim();
            Matcher matcher = sysPropVarPattern.matcher(s);
            int previousEnd = 0;
            StringBuilder sb = new StringBuilder();
            String varName, varValue;
            while (matcher.find()) {
                varName = matcher.group(1);
                varValue = System.getProperty(varName);
                if (varValue == null) throw new Exception(
                            "No Sys Property set for variable '"
                            + varName + "' in property value (" + s + ").");
                sb.append(s.substring(previousEnd, matcher.start())
                            + ((varValue == null) ? matcher.group() : varValue));
                previousEnd = matcher.end();
            }
            String propPath = (previousEnd < 1) ? s
                                     : (sb.toString() + s.substring(previousEnd));
            application.log("USING (" + propPath + ')');
            File propFile = new File(propPath);
            if (!propFile.isFile())
                throw new Exception("HyperSQL server config file '"
                        + propFile.getAbsolutePath() + "' not a file");
            if (!propPath.endsWith(".properties"))
                throw new Exception("HyperSQL server config file '"
                        + propFile.getAbsolutePath()
                        + "' does not end with .properties");
            if (!propFile.isAbsolute())
                throw new Exception(
                        "Specified 'hsqldb.server.propertiespath' not "
                        + "absolute: " + propPath);

            server = new Server();
            server.setProperties(new ServerProperties(
                    ServerConstants.SC_PROTOCOL_HSQL, propFile));
            server.start();
        } catch (Exception e) {
            application.log("Fatal failure to initialize HyperSQL.  "
                    + e.getMessage());
            throw new IllegalStateException(
                    "Fatal failure to initialize HyperSQL", e);
        }
    }
}
