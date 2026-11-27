package com.viscosiety.viscorunner.apigroups;

import java.util.List;

import org.apache.catalina.valves.rewrite.RewriteMap;

/**
 * {@code RewriteMap apigroups com.viscosiety.viscorunner.apigroups.ApiGroupsRewriteMap} in rewrite.config: answers
 * {@code yes} for a listed endpoint group so the routing rule rewrites {@code /runtime/api/<group>/...} to the group's
 * servlet. The list is read once, when Tomcat creates the map while loading the rules (the setting is made before
 * Tomcat starts). It is read in the constructor because Tomcat's RewriteValve calls {@code setParameters} only for a
 * map line that has parameters, and this one has none.
 */
public class ApiGroupsRewriteMap implements RewriteMap {

    private final List<String> groups;

    public ApiGroupsRewriteMap() {
        String raw = System.getProperty(ApiGroupNames.PROPERTY);
        groups = ApiGroupNames.parse(raw != null ? raw : System.getenv(ApiGroupNames.PROPERTY));
    }

    @Override
    public String setParameters(String params) {
        return null;
    }

    @Override
    public String lookup(String key) {
        return key != null && groups.contains(key) ? "yes" : null;
    }
}
