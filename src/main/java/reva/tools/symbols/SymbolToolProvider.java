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
package reva.tools.symbols;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.symbol.SymbolType;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import reva.tools.AbstractToolProvider;
import reva.util.AddressUtil;
import reva.util.SymbolUtil;

/**
 * Tool provider for symbol-related operations.
 */
public class SymbolToolProvider extends AbstractToolProvider {
    /**
     * Constructor
     * @param server The MCP server
     */
    public SymbolToolProvider(McpSyncServer server) {
        super(server);
    }

    @Override
    public void registerTools() {
        registerSymbolsCountTool();
        registerSymbolsTool();
    }

    /**
     * Register a tool to get the count of symbols in a program
     */
    private void registerSymbolsCountTool() {
        // Define schema for the tool
        Map<String, Object> properties = new HashMap<>();
        properties.put("programPath", Map.of(
            "type", "string",
            "description", "Path in the Ghidra Project to the program to get symbol count from"
        ));
        properties.put("includeExternal", Map.of(
            "type", "boolean",
            "description", "Whether to include external symbols in the count",
            "default", false
        ));
        properties.put("filterDefaultNames", Map.of(
            "type", "boolean",
            "description", "Whether to filter out default Ghidra generated names like FUN_, DAT_, etc.",
            "default", true
        ));
        properties.put("namePattern", Map.of(
            "type", "string",
            "description", "Optional regex pattern to filter symbols by name (e.g. '(?i)parse.*string' to find symbols with 'parse' and 'string' in their name)"
        ));

        List<String> required = List.of("programPath");

        // Create the tool
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get-symbols-count")
            .title("Get Symbols Count")
            .description("Get the total count of symbols in the program (use this before calling get-symbols to plan pagination). Optionally filter by name pattern.")
            .inputSchema(createSchema(properties, required))
            .build();

        // Register the tool with a handler
        registerTool(tool, (exchange, request) -> {
            // Get program and parameters using helper methods
            Program program = getProgramFromArgs(request);
            boolean includeExternal = getOptionalBoolean(request, "includeExternal", false);
            boolean filterDefaultNames = getOptionalBoolean(request, "filterDefaultNames", true);
            String namePatternStr = getOptionalString(request, "namePattern", null);

            // Compile name pattern if provided
            Pattern nameRegex = null;
            if (namePatternStr != null && !namePatternStr.trim().isEmpty()) {
                try {
                    nameRegex = Pattern.compile(namePatternStr);
                } catch (PatternSyntaxException e) {
                    return createErrorResult("Invalid regex pattern: " + e.getMessage());
                }
            }

            // Count the symbols
            SymbolTable symbolTable = program.getSymbolTable();
            SymbolIterator symbolIterator = symbolTable.getAllSymbols(true);

            int count = 0;
            while (symbolIterator.hasNext()) {
                Symbol symbol = symbolIterator.next();

                // Skip external symbols if not included
                if (!includeExternal && symbol.isExternal()) {
                    continue;
                }

                if (filterDefaultNames && SymbolUtil.isDefaultSymbolName(symbol.getName())) {
                    continue;
                }

                // Apply name pattern filter
                if (nameRegex != null && !nameRegex.matcher(symbol.getName()).find()) {
                    continue;
                }

                count++;
            }

            // Create result data
            Map<String, Object> countData = new HashMap<>();
            countData.put("count", count);
            countData.put("includeExternal", includeExternal);
            countData.put("filterDefaultNames", filterDefaultNames);
            if (namePatternStr != null) {
                countData.put("namePattern", namePatternStr);
            }

            return createJsonResult(countData);
        });
    }

    /**
     * Register a tool to get symbols from a program with pagination
     */
    private void registerSymbolsTool() {
        // Define schema for the tool
        Map<String, Object> properties = new HashMap<>();
        properties.put("programPath", Map.of(
            "type", "string",
            "description", "Path in the Ghidra Project to the program to get symbols from"
        ));
        properties.put("includeExternal", Map.of(
            "type", "boolean",
            "description", "Whether to include external symbols in the result",
            "default", false
        ));
        properties.put("startIndex", Map.of(
            "type", "integer",
            "description", "Starting index for pagination (0-based)",
            "default", 0
        ));
        properties.put("maxCount", Map.of(
            "type", "integer",
            "description", "Maximum number of symbols to return (recommend using get-symbols-count first and using chunks of 200)",
            "default", 200
        ));
        properties.put("filterDefaultNames", Map.of(
            "type", "boolean",
            "description", "Whether to filter out default Ghidra generated names like FUN_, DAT_, etc.",
            "default", true
        ));
        properties.put("namePattern", Map.of(
            "type", "string",
            "description", "Optional regex pattern to filter symbols by name (e.g. '(?i)parse.*string' to find symbols with 'parse' and 'string' in their name)"
        ));
        properties.put("symbolTypes", Map.of(
            "type", "array",
            "description", "Optional list of symbol types to include (e.g. ['Function', 'Label', 'Class']). If not specified, all types are included.",
            "items", Map.of("type", "string")
        ));

        List<String> required = List.of("programPath");

        // Create the tool
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get-symbols")
            .title("Get Symbols")
            .description("Get symbols from the selected program with pagination and optional filtering. Use namePattern to search for symbols by regex. Much faster than search-decompilation for finding functions or data by name.")
            .inputSchema(createSchema(properties, required))
            .build();

        // Register the tool with a handler
        registerTool(tool, (exchange, request) -> {
            // Get program and parameters using helper methods
            Program program = getProgramFromArgs(request);
            boolean includeExternal = getOptionalBoolean(request, "includeExternal", false);
            PaginationParams pagination = getPaginationParams(request, 200);
            boolean filterDefaultNames = getOptionalBoolean(request, "filterDefaultNames", true);
            String namePatternStr = getOptionalString(request, "namePattern", null);
            List<String> symbolTypeFilter = getOptionalStringList(request.arguments(), "symbolTypes", null);

            // Compile name pattern if provided
            Pattern nameRegex = null;
            if (namePatternStr != null && !namePatternStr.trim().isEmpty()) {
                try {
                    nameRegex = Pattern.compile(namePatternStr);
                } catch (PatternSyntaxException e) {
                    return createErrorResult("Invalid regex pattern: " + e.getMessage());
                }
            }

            // Build symbol type filter set
            Set<String> typeFilter = null;
            if (symbolTypeFilter != null && !symbolTypeFilter.isEmpty()) {
                typeFilter = new HashSet<>();
                for (String t : symbolTypeFilter) {
                    typeFilter.add(t.toUpperCase());
                }
            }

            // Get the symbols with pagination
            List<Map<String, Object>> symbolData = new ArrayList<>();
            SymbolTable symbolTable = program.getSymbolTable();
            SymbolIterator symbolIterator = symbolTable.getAllSymbols(true);

            int currentIndex = 0;

            while (symbolIterator.hasNext()) {
                Symbol symbol = symbolIterator.next();

                // Skip external symbols if not included
                if (!includeExternal && symbol.isExternal()) {
                    continue;
                }

                // Skip default names if filtering is enabled
                if (filterDefaultNames && SymbolUtil.isDefaultSymbolName(symbol.getName())) {
                    continue;
                }

                // Apply name pattern filter
                if (nameRegex != null && !nameRegex.matcher(symbol.getName()).find()) {
                    continue;
                }

                // Apply symbol type filter
                if (typeFilter != null && !typeFilter.contains(symbol.getSymbolType().toString().toUpperCase())) {
                    continue;
                }

                int index = currentIndex++;

                // Skip symbols before the start index
                if (index < pagination.startIndex()) {
                    continue;
                }

                // Stop after we've collected maxCount symbols
                if (symbolData.size() >= pagination.maxCount()) {
                    break;
                }

                // Collect symbol data
                symbolData.add(createSymbolInfo(symbol));
            }

            // Create pagination metadata
            Map<String, Object> paginationInfo = new HashMap<>();
            paginationInfo.put("startIndex", pagination.startIndex());
            paginationInfo.put("requestedCount", pagination.maxCount());
            paginationInfo.put("actualCount", symbolData.size());
            paginationInfo.put("nextStartIndex", pagination.startIndex() + symbolData.size());
            paginationInfo.put("totalProcessed", currentIndex);
            paginationInfo.put("includeExternal", includeExternal);
            paginationInfo.put("filterDefaultNames", filterDefaultNames);
            if (namePatternStr != null) {
                paginationInfo.put("namePattern", namePatternStr);
            }

            paginationInfo.put("symbols", symbolData);
            return createJsonResult(paginationInfo);
        });
    }

    /**
     * Create a map of symbol information
     * @param symbol The symbol to extract information from
     * @return Map containing symbol properties
     */
    private Map<String, Object> createSymbolInfo(Symbol symbol) {
        Map<String, Object> symbolInfo = new HashMap<>();
        symbolInfo.put("name", symbol.getName());
        symbolInfo.put("address", AddressUtil.formatAddress(symbol.getAddress()));
        symbolInfo.put("namespace", symbol.getParentNamespace().getName());
        symbolInfo.put("id", symbol.getID());
        symbolInfo.put("symbolType", symbol.getSymbolType().toString());
        symbolInfo.put("isPrimary", symbol.isPrimary());
        symbolInfo.put("isExternal", symbol.isExternal());

        // For function symbols, add specific data
        if (symbol.getSymbolType() == SymbolType.FUNCTION) {
            symbolInfo.put("isFunction", true);
        } else {
            symbolInfo.put("isFunction", false);
        }

        return symbolInfo;
    }
}
