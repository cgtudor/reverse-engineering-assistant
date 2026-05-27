/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reva.tools.listing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import reva.tools.AbstractToolProvider;
import reva.util.AddressUtil;

/**
 * Tool provider for disassembly listing search operations.
 * Searches the instruction listing directly without decompilation,
 * providing near-instant results for instruction pattern matching.
 */
public class ListingToolProvider extends AbstractToolProvider {

    public ListingToolProvider(McpSyncServer server) {
        super(server);
    }

    @Override
    public void registerTools() {
        registerSearchListingTool();
    }

    private void registerSearchListingTool() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("programPath", Map.of(
            "type", "string",
            "description", "Path in the Ghidra Project to the program to search"
        ));
        properties.put("pattern", Map.of(
            "type", "string",
            "description", "Regular expression pattern to match against instruction text (mnemonic + operands, e.g. 'CALL.*printf', 'MOV.*EAX.*0x', 'CMP.*dword.*\\[rbp')"
        ));
        properties.put("maxResults", Map.of(
            "type", "integer",
            "description", "Maximum number of results to return",
            "default", 100
        ));
        properties.put("caseSensitive", Map.of(
            "type", "boolean",
            "description", "Whether the search should be case sensitive",
            "default", false
        ));
        properties.put("searchComments", Map.of(
            "type", "boolean",
            "description", "Also search EOL and plate comments on instructions",
            "default", false
        ));
        properties.put("mnemonicFilter", Map.of(
            "type", "string",
            "description", "Optional: only search instructions with this mnemonic (e.g. 'CALL', 'JMP', 'MOV'). Faster than including it in the regex pattern."
        ));
        properties.put("startAddress", Map.of(
            "type", "string",
            "description", "Optional: start searching from this address (e.g. '0x00401000'). Useful for narrowing search to a specific region."
        ));
        properties.put("endAddress", Map.of(
            "type", "string",
            "description", "Optional: stop searching at this address. Used with startAddress to limit search range."
        ));

        List<String> required = List.of("programPath", "pattern");

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("search-listing")
            .title("Search Disassembly Listing")
            .description("Search for patterns in the disassembly listing (instruction text) without decompilation. " +
                "Much faster than search-decompilation for finding instruction patterns, function calls, " +
                "operand patterns, and comments. Use this first before resorting to search-decompilation.")
            .inputSchema(createSchema(properties, required))
            .build();

        registerTool(tool, (exchange, request) -> {
            Program program = getProgramFromArgs(request);
            String patternStr = getString(request, "pattern");
            int maxResults = getOptionalInt(request, "maxResults", 100);
            boolean caseSensitive = getOptionalBoolean(request, "caseSensitive", false);
            boolean searchComments = getOptionalBoolean(request, "searchComments", false);
            String mnemonicFilter = getOptionalString(request, "mnemonicFilter", null);
            String startAddressStr = getOptionalString(request, "startAddress", null);
            String endAddressStr = getOptionalString(request, "endAddress", null);

            if (patternStr.trim().isEmpty()) {
                return createErrorResult("Search pattern cannot be empty");
            }

            // Compile regex
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern regex;
            try {
                regex = Pattern.compile(patternStr, flags);
            } catch (PatternSyntaxException e) {
                return createErrorResult("Invalid regex pattern: " + e.getMessage());
            }

            // Normalize mnemonic filter
            if (mnemonicFilter != null) {
                mnemonicFilter = mnemonicFilter.trim().toUpperCase();
                if (mnemonicFilter.isEmpty()) {
                    mnemonicFilter = null;
                }
            }

            // Resolve address range
            Listing listing = program.getListing();
            FunctionManager functionManager = program.getFunctionManager();
            InstructionIterator instructions;

            if (startAddressStr != null || endAddressStr != null) {
                Address startAddr = startAddressStr != null
                    ? AddressUtil.resolveAddressOrSymbol(program, startAddressStr) : program.getMinAddress();
                Address endAddr = endAddressStr != null
                    ? AddressUtil.resolveAddressOrSymbol(program, endAddressStr) : program.getMaxAddress();

                if (startAddr == null) {
                    return createErrorResult("Could not resolve startAddress: " + startAddressStr);
                }
                if (endAddr == null) {
                    return createErrorResult("Could not resolve endAddress: " + endAddressStr);
                }

                instructions = listing.getInstructions(
                    program.getAddressFactory().getAddressSet(startAddr, endAddr), true);
            } else {
                instructions = listing.getInstructions(true);
            }

            // Search instructions
            List<Map<String, Object>> results = new ArrayList<>();

            while (instructions.hasNext() && results.size() < maxResults) {
                Instruction instr = instructions.next();

                // Fast mnemonic pre-filter (avoids regex on non-matching instructions)
                if (mnemonicFilter != null && !instr.getMnemonicString().toUpperCase().equals(mnemonicFilter)) {
                    continue;
                }

                String instrText = instr.toString();
                Matcher matcher = regex.matcher(instrText);
                boolean matched = matcher.find();

                // Optionally search comments too
                String matchedComment = null;
                if (!matched && searchComments) {
                    String eolComment = instr.getComment(CommentType.EOL);
                    if (eolComment != null && regex.matcher(eolComment).find()) {
                        matched = true;
                        matchedComment = eolComment;
                    }
                    if (!matched) {
                        String plateComment = instr.getComment(CommentType.PLATE);
                        if (plateComment != null && regex.matcher(plateComment).find()) {
                            matched = true;
                            matchedComment = plateComment;
                        }
                    }
                }

                if (matched) {
                    Address addr = instr.getAddress();
                    Map<String, Object> result = new HashMap<>();
                    result.put("address", AddressUtil.formatAddress(addr));
                    result.put("instruction", instrText);
                    result.put("mnemonic", instr.getMnemonicString());

                    // Find containing function
                    Function func = functionManager.getFunctionContaining(addr);
                    if (func != null) {
                        result.put("functionName", func.getName());
                        result.put("functionAddress", AddressUtil.formatAddress(func.getEntryPoint()));
                    }

                    if (matchedComment != null) {
                        result.put("matchedComment", matchedComment);
                    }

                    results.add(result);
                }
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("programName", program.getName());
            resultData.put("pattern", patternStr);
            resultData.put("caseSensitive", caseSensitive);
            resultData.put("resultsCount", results.size());
            resultData.put("maxResults", maxResults);
            if (mnemonicFilter != null) {
                resultData.put("mnemonicFilter", mnemonicFilter);
            }
            resultData.put("results", results);

            return createJsonResult(resultData);
        });
    }
}
