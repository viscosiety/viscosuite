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

package com.viscosiety.hl7util;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.parser.XMLParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * hl7util — command-line tool for sending HL7v2 messages over MLLP.
 *
 * <pre>
 *   java -jar hl7util.jar [options] [message-file]
 * </pre>
 *
 * Exit codes:
 * <ul>
 *   <li>0 — ACK received with MSA.1=AA (application accept)</li>
 *   <li>1 — ACK received with MSA.1=AE or AR (application/accept reject)</li>
 *   <li>2 — Connection or argument error</li>
 * </ul>
 */
public class Hl7Util {

    private static final String DEFAULT_HOST            = "localhost";
    private static final int    DEFAULT_PORT            = 2575;
    private static final int    DEFAULT_CONNECT_TIMEOUT = 5000;
    private static final int    DEFAULT_SOCKET_TIMEOUT  = 30000;
    private static final String DEFAULT_CHARSET         = "UTF-8";

    public static void main(String[] args) {
        System.exit(new Hl7Util().run(args));
    }

    int run(String[] args) {
        // --- defaults ---
        String  host           = DEFAULT_HOST;
        int     port           = DEFAULT_PORT;
        int     connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        int     socketTimeout  = DEFAULT_SOCKET_TIMEOUT;
        Charset charset        = Charset.forName(DEFAULT_CHARSET);
        boolean verbose        = false;
        boolean fromStdin      = false;
        boolean xmlInput       = false;
        String  messageArg     = null;
        String  fileArg        = null;

        // --- parse arguments ---
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-h", "--host"            -> host           = args[++i];
                    case "-p", "--port"            -> port           = requireInt(args[++i], "--port");
                    case "-f", "--file"            -> fileArg        = args[++i];
                    case "-m", "--message"         -> messageArg     = args[++i];
                    case "--stdin"                 -> fromStdin      = true;
                    case "--xml"                   -> xmlInput       = true;
                    case "--connect-timeout"       -> connectTimeout = requireInt(args[++i], "--connect-timeout");
                    case "--socket-timeout"        -> socketTimeout  = requireInt(args[++i], "--socket-timeout");
                    case "--charset"               -> charset        = Charset.forName(args[++i]);
                    case "-v", "--verbose"         -> verbose        = true;
                    case "-?", "--help"            -> { printHelp(); return 0; }
                    default -> {
                        if (!args[i].startsWith("-")) {
                            fileArg = args[i]; // positional: treat as file path
                        } else {
                            err("Unknown option: " + args[i] + "  (use --help for usage)");
                            return 2;
                        }
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            err("Missing value for last option  (use --help for usage)");
            return 2;
        }

        // --- load and normalise message ---
        String message;
        try {
            if (messageArg != null) {
                // allow literal \r in the string argument as segment separator
                message = messageArg.replace("\\r", "\r");
            } else if (fromStdin) {
                message = readStream(System.in, charset);
            } else if (fileArg != null) {
                message = Files.readString(Path.of(fileArg), charset);
            } else {
                err("No message source provided — use --file, --message, --stdin, or --help");
                return 2;
            }

            if (xmlInput) {
                message = xmlToPipeDelimited(message);
            }

            // normalise line endings to CR (HL7v2 segment delimiter)
            message = message.replace("\r\n", "\r").replace("\n", "\r").stripTrailing();

        } catch (Exception e) {
            err("Failed to load message: " + e.getMessage());
            return 2;
        }

        // --- send over MLLP ---
        try (MllpClient client = new MllpClient(host, port, connectTimeout, socketTimeout, charset)) {
            log("Connecting to " + host + ":" + port + " ...");
            client.connect();
            log("Sending: " + "\n" + message.replace("\r", "\n") + "\n");
            String ack = client.send(message);

            String response = ack.replace("\r", "\n");;
            log("Response: " + "\n" + response + "\n");
            String ackCode = extractAckCode(ack).replace("\r", "\n");

            log("ACK: " + ackCode);

            if (verbose) {
                System.out.println(ack);
            }

            return "AA".equals(ackCode) ? 0 : 1;

        } catch (IOException e) {
            err("Connection error: " + e.getMessage());
            return 2;
        }
    }

    // --- helpers ---

    private String readStream(InputStream in, Charset charset) throws IOException {
        return new String(in.readAllBytes(), charset);
    }

    private String xmlToPipeDelimited(String xml) throws Exception {
        try (HapiContext ctx = new DefaultHapiContext()) {
            XMLParser  xmlParser  = ctx.getXMLParser();
            PipeParser pipeParser = ctx.getPipeParser();
            return pipeParser.encode(xmlParser.parse(xml));
        }
    }

    private String extractAckCode(String ack) {
        for (String segment : ack.split("\r")) {
            if (segment.startsWith("MSA")) {
                String[] fields = segment.split("\\|");
                if (fields.length > 1) return fields[1].trim();
            }
        }
        return "UNKNOWN";
    }

    private static int requireInt(String value, String argName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + argName + ": " + value);
        }
    }

    private static void log(String msg) {
        System.err.println("[hl7util] " + msg);
    }

    private static void err(String msg) {
        System.err.println("[hl7util] ERROR: " + msg);
    }

    private static void printHelp() {
        System.out.println("""
                hl7util — send an HL7v2 message over MLLP

                Usage:
                  java -jar hl7util.jar [options] [message-file]

                Message source (exactly one required):
                  [message-file]          Path to a pipe-delimited HL7v2 file (positional)
                  -f, --file <file>       Path to a pipe-delimited HL7v2 file
                  -m, --message <msg>     Inline HL7v2 message; use \\r as segment separator
                      --stdin             Read message from standard input

                Target:
                  -h, --host <host>       MLLP target host  (default: localhost)
                  -p, --port <port>       MLLP target port  (default: 2575)

                Options:
                      --xml               Input is HL7v2 XML Encoding Syntax (urn:hl7-org:v2xml);
                                          convert to pipe-delimited before sending
                      --connect-timeout   TCP connect timeout in ms  (default: 5000)
                      --socket-timeout    Socket read timeout in ms  (default: 30000)
                      --charset <name>    Wire character encoding     (default: UTF-8)
                  -v, --verbose           Print the full ACK message to stdout
                  -?, --help              Show this help message

                Exit codes:
                  0   MSA.1 = AA  (application accept)
                  1   MSA.1 = AE or AR  (application / accept reject)
                  2   Connection error or invalid arguments

                Examples:
                  java -jar hl7util.jar message.hl7
                  java -jar hl7util.jar --host hl7.example.com --port 2575 --file admit.hl7 --verbose
                  java -jar hl7util.jar -h localhost -p 2575 -m "MSH|^~\\&|Test|Test|rcv|rcv|20260101||ADT^A01^ADT_A01|1|P|2.5\\rPID|||12345"
                  cat message.hl7 | java -jar hl7util.jar --stdin
                  java -jar hl7util.jar --xml --file bundle.xml -h localhost
                """);
    }
}
