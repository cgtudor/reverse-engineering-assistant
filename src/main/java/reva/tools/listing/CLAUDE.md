# CLAUDE.md - Listing Tools Package

This file provides guidance to Claude Code when working with the listing tools package in ReVa.

## Package Overview

The listing tools package (`reva.tools.listing`) provides MCP tools for searching the disassembly listing directly, without decompilation. This is orders of magnitude faster than `search-decompilation` for instruction-level pattern matching.

## Key Tools

- `search-listing` - Search disassembly text (mnemonic + operands) by regex

## When to Use

Use `search-listing` **instead of** `search-decompilation` when:
- Looking for specific instruction patterns (`CALL.*printf`, `MOV.*EAX.*0x`)
- Finding function calls by name in the disassembly
- Searching for operand patterns or constants in instructions
- Searching comments (EOL/plate) on instructions
- You need fast results (milliseconds vs minutes)

Use `search-decompilation` only when you need patterns that only exist in decompiled C (variable names, type casts, high-level constructs).

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `programPath` | string | required | Program path |
| `pattern` | string | required | Regex against instruction text |
| `maxResults` | int | 100 | Max results to return |
| `caseSensitive` | bool | false | Case-sensitive matching |
| `searchComments` | bool | false | Also search EOL/plate comments |
| `mnemonicFilter` | string | null | Pre-filter by mnemonic (e.g. `"CALL"`) — faster than regex |
| `startAddress` | string | null | Start of address range |
| `endAddress` | string | null | End of address range |

## Response Format

```json
{
  "programName": "nwserver-linux",
  "pattern": "CALL.*printf",
  "caseSensitive": false,
  "resultsCount": 5,
  "maxResults": 100,
  "results": [
    {
      "address": "0x00401234",
      "instruction": "CALL printf",
      "mnemonic": "CALL",
      "functionName": "main",
      "functionAddress": "0x00401000"
    }
  ]
}
```

## Implementation Details

- Uses `Listing.getInstructions()` — reads indexed DB records, no decompilation
- `mnemonicFilter` is checked before regex for fast short-circuiting
- `Instruction.toString()` returns mnemonic + operands (e.g. `"MOV EAX,dword ptr [RBP + -0x4]"`)
- Comments use `CodeUnit.EOL_COMMENT` and `CodeUnit.PLATE_COMMENT`
- Address range uses `AddressUtil.resolveAddressOrSymbol()` — accepts hex or symbol names
- Containing function resolved via `FunctionManager.getFunctionContaining()`

## Important Notes

- This tool searches the **disassembly**, not decompiled C code
- Instruction text format depends on the processor/language (x86, ARM, etc.)
- For x86, mnemonics are uppercase (CALL, MOV, JMP) but operands may be mixed case
- Default case-insensitive matching handles most use cases
- `mnemonicFilter` is always compared uppercase regardless of `caseSensitive`
