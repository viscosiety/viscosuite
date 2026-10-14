/*
 * Copyright 2026 Viscosiety B.V.
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
 */
package com.viscosiety.pipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lemmabase.lemma.Response;
import com.lemmabase.lemma.RuleResult;
import org.frankframework.util.XmlEncodingUtils;

/** Pure helpers of {@link LemmaPipe}: the engine's response as XML, JSON and per-rule text. */
final class LemmaResults {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LemmaResults() {
    }

    static Optional<String> valueText(RuleResult result) {
        String text = switch (result) {
            case RuleResult.Text r -> r.result();
            case RuleResult.BooleanValue r -> r.result();
            case RuleResult.Number r -> r.result();
            case RuleResult.Measure r -> r.result();
            case RuleResult.Date r -> r.result();
            case RuleResult.Time r -> r.result();
            case RuleResult.Ratio r -> r.result();
            case RuleResult.Range r -> r.result();
            case RuleResult.Calendar r -> r.result();
            case RuleResult.ListResult r -> r.result();
            default -> null;
        };
        return Optional.ofNullable(text);
    }

    static List<String> missingData(Map<String, RuleResult> results) {
        TreeSet<String> names = new TreeSet<>();
        results.values().forEach(r -> {
            if (r instanceof RuleResult.MissingData missing) {
                names.addAll(missing.missingData());
            }
        });
        return List.copyOf(names);
    }

    static Map<String, String> vetoes(Map<String, RuleResult> results) {
        Map<String, String> vetoes = new LinkedHashMap<>();
        results.forEach((rule, r) -> {
            if (r instanceof RuleResult.Veto veto) {
                vetoes.put(rule, veto.vetoReason() == null ? "" : veto.vetoReason());
            }
        });
        return vetoes;
    }

    static List<String> unsupported(Map<String, RuleResult> results) {
        List<String> rules = new ArrayList<>();
        results.forEach((rule, r) -> {
            if (r instanceof RuleResult.Spec) {
                rules.add(rule);
            }
        });
        return rules;
    }

    static String xml(Response response, boolean explain) {
        StringBuilder xml = new StringBuilder("<lemmaResult spec=\"")
                .append(XmlEncodingUtils.encodeChars(response.spec()))
                .append("\" effective=\"").append(XmlEncodingUtils.encodeChars(String.valueOf(response.effective())))
                .append("\">");
        response.results().forEach((rule, r) -> {
            xml.append("<result rule=\"").append(XmlEncodingUtils.encodeChars(rule)).append('"');
            if (r instanceof RuleResult.Veto veto) {
                xml.append(" vetoed=\"true\">").append(XmlEncodingUtils.encodeChars(nullToEmpty(veto.vetoReason())));
            } else {
                xml.append(" type=\"").append(XmlEncodingUtils.encodeChars(r.ruleType())).append("\">")
                        .append(XmlEncodingUtils.encodeChars(valueText(r).orElse("")));
            }
            if (explain && r.explanation() != null) {
                xml.append("<explanation>").append(XmlEncodingUtils.encodeChars(explanationJson(r))).append("</explanation>");
            }
            xml.append("</result>");
        });
        return xml.append("</lemmaResult>").toString();
    }

    static String json(Response response, boolean explain) {
        ObjectNode root = JSON.createObjectNode();
        root.put("spec", response.spec());
        root.put("effective", String.valueOf(response.effective()));
        ObjectNode results = root.putObject("results");
        response.results().forEach((rule, r) -> {
            ObjectNode node = results.putObject(rule);
            node.put("type", r.ruleType());
            if (r instanceof RuleResult.Veto veto) {
                node.put("vetoed", true);
                node.put("reason", nullToEmpty(veto.vetoReason()));
            } else {
                node.put("result", valueText(r).orElse(null));
            }
            if (explain && r.explanation() != null) {
                node.set("explanation", JSON.valueToTree(r.explanation()));
            }
        });
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot render the Lemma result of spec " + response.spec(), e);
        }
    }

    private static String explanationJson(RuleResult r) {
        try {
            return JSON.writeValueAsString(r.explanation());
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
