/*
 * Copyright 2000-2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @test
 * @summary ResolveSymbolsTest verifies that the set of impotered symbols is
 *          limited by the predifed symbols list
 *
 * @comment What the test actually does?
 *          - It dumps all available in some environment symbols(hereinafter - external symbols).
 *          - Dumps all exported from JBR symbols. Dumps all imported by JBR symbols and checks
 *            for their presence in the JBR or environment.
 *
 *          From what environment comes the external symbols?
 *          They are from the Docker images used to build JBR and Docker images for JCEF.
 *
 *          How the symbols in the dataset are taken?
 *          There are scripts used for that: jb/build/externalLibs/aarch64/dump_aarch64.sh and
 *          jb/build/externalLibs/x86_64/dump_x86_64.sh
 *
 *          JBR got a new dependency. How to update the test data?
 *          - Run a docker container made from the corresponding image in jb/project/docker
 *          - Find the library whose symbols are missing. This utilities might help:
 *            $ ldconfig -p | grep libNameImLookingFor
 *            $ ldd nameOfJbrLibrary
 *
 *          - Dump ELF:
 *            $ echo Library: $lib_path" >> $lib_name.txt
 *            $ echo Package: $(rpm -q --whatprovides $lib_path) >> $lib_name.txt
 *            $ echo >> $lib_name.txt
 *            $ readelf --wide --dyn-syms $lib_path >> $lib_name.txt
 *            Put $lib_name.txt to the corresponding directory jb/build/externalLibs/aarch64 or
 *            jb/build/externalLibs/x86_64
 *
 * @requires (os.family == "linux")
 * @run main ResolveSymbolsTest
 */

public class ResolveSymbolsTest {

    private static class ElfSymbol {
        String name;
        String type;
        String bind;
        String sectionNumber;
    }

    /* Parses the output ofr linux readelf(binutils) that tools like:
     * $ readelf -W --dyn-syms myLib.so
     *
     * Symbol table '.dynsym' contains 97 entries:
     *    Num:    Value          Size Type    Bind   Vis      Ndx Name
     *      6: 0000000000000000     0 FUNC    WEAK   DEFAULT  UND __cxa_finalize@GLIBC_2.17 (2)
     *      7: 0000000000000000     0 NOTYPE  GLOBAL DEFAULT  UND __cxa_end_catch
     *      8: 0000000000000000     0 FUNC    GLOBAL DEFAULT  UND __cxa_atexit@GLIBC_2.17 (2)
     *     28: 0000000000003594    28 FUNC    WEAK   DEFAULT   11 _ZNK9__gnu_cxx13new_allocatorISsE8max_sizeEv
     *
     *
     * Columns description:
     * Num   - The symbol number
     * Value - The address of the Symbol
     * Size  - The size of the symbol
     * Type  - symbol type: Func = Function, Object, File (source file name), Section = memory section, Notype = untyped
     *        absolute symbol or undefined
     * Bind  - GLOBAL binding means the symbol is visible outside the file. LOCAL binding is visible only in the file.
     *         WEAK is like global, the symbol can be overridden.
     * Vis   - Symbols can be default, protected, hidden or internal.
     * Ndx   - The section number the symbol is in. ABS means absolute: not adjusted to any section address's relocation
     * Name  - symbol name
     */
    private static List<ElfSymbol> parseElf(String readElfData) {
        String[] lines = readElfData.split("\n");

        int i = 0;
        while (i < lines.length && !lines[i].contains("'.dynsym'")) {
            ++i;
        }
        ++i;
        if (i >= lines.length) {
            throw new RuntimeException("readelf: no dynsym table");
        }

        List<String> tableHeaders = Arrays.asList(lines[i].split("\\s+"));
        final int nameCol = tableHeaders.indexOf("Name");
        final int typeCol = tableHeaders.indexOf("Type");
        final int bindCol = tableHeaders.indexOf("Bind");
        final int ndxCol = tableHeaders.indexOf("Ndx");
        final int lastCol = Collections.max(Arrays.asList(nameCol, typeCol, bindCol, ndxCol));
        if (typeCol == -1 || bindCol == -1 || ndxCol == -1) {
            throw new RuntimeException("readelf: the dynsym must have Type, Bind and Ndx columns, but it has " + tableHeaders);
        }
        ++i;
        if (i >= lines.length) {
            throw new RuntimeException("readelf: dynsym table is empty");
        }

        List<ElfSymbol> result = new ArrayList<>();
        for (; i < lines.length; ++i) {
            if (lines[i].isBlank()) {
                return result;
            }

            String[] row = lines[i].split("\\s+");
            if (lastCol >= row.length) {
                // that probably means that the name attribute is empty
                continue;
            }
            ElfSymbol symbol = new ElfSymbol();
            symbol.name = row[nameCol].split("@")[0]; // get rid of suffixes like "@GLIBC_2.2.5 (2)"
            symbol.type = row[typeCol];
            symbol.bind = row[bindCol];
            symbol.sectionNumber = row[ndxCol];
            result.add(symbol);
        }

        return result;
    }

    private static String runReadElf(Path path) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("readelf --wide --dyn-syms " + path);
        String output = new BufferedReader(
                new InputStreamReader(process.getInputStream())).lines().collect(Collectors.joining("\n"));
        if (process.waitFor() != 0) {
            return null;
        }
        return output;
    }

    private static void checkReadElf() throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("readelf --version");
        if (process.waitFor() != 0) {
            throw new RuntimeException("Failed to run readelf");
        }
    }

    private static boolean functionSymbolsFilter(String fileName, ElfSymbol symbol) {
        // check for exceptions:
        // https://src.fedoraproject.org/rpms/chromium/blob/f32/f/chromium-84.0.4147.89-epel7-old-cups.patch
        // the symbols avaliability is checked in runtime
        if (fileName.equals("libcef.so") && symbol.name.equals("httpConnect2")) {
            return false;
        }

        return !symbol.name.isEmpty() &&
                (symbol.type.equals("FUNC") || symbol.type.equals("IFUNC")) &&
                (symbol.bind.equals("WEAK") || symbol.bind.equals("GLOBAL"));
    }

    private static String getArch() {
        String arch = System.getProperty("os.arch");
        if (arch.equals("x86_64") || arch.equals("amd64")) {
            return "x86_64";
        } else if (arch.equals("aarch64")) {
            return "aarch64";
        }
        throw new RuntimeException("Unknown os.arch: " + arch);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        checkReadElf();
        String javaHome = System.getProperty("java.home");

        Set<String> exportedSymbols = new HashSet<>();

        // ========================= Collect symbols from the external libs =========================
        String libsPath = Objects.requireNonNull(ResolveSymbolsTest.class.getResource("externalLibs/" + getArch())).getPath();
        try (Stream<Path> stream = Files.list(Paths.get(libsPath))) {
            stream.filter(path -> path.toString().endsWith(".txt")).forEach(path -> {
                try {
                    List<String> symbols = parseElf(Files.readString(path)).stream()
                            .filter(elfSymbol -> ResolveSymbolsTest.functionSymbolsFilter(path.getFileName().toString(), elfSymbol))
                            .filter(elfSymbol -> !elfSymbol.sectionNumber.equals("UND"))
                            .map(elfSymbol -> elfSymbol.name)
                            .toList();
                    exportedSymbols.addAll(symbols);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // ========================= Collect symbols in JBR =========================
        List<Path> binaries;
        try (Stream<Path> walk = Files.walk(Paths.get(javaHome))) {
            binaries = walk
                    .filter(path -> !Files.isDirectory(path))
                    .filter(path -> Files.isExecutable(path) || path.toString().endsWith(".so")).toList();
        }

        // A map (Library name) -> (List of imported symbols)
        Map<String, List<String>> importedSymbols = new HashMap<>();
        for (Path path : binaries) {
            String elfData = runReadElf(path);
            if (elfData == null) {
                // the candidate doesn't have elf.
                continue;
            }
            List<ElfSymbol> elfSymbols = parseElf(elfData);
            for (ElfSymbol symbol : elfSymbols) {
                if (!functionSymbolsFilter(path.getFileName().toString(), symbol)) {
                    continue;
                }
                if (symbol.sectionNumber.equals("UND")) {
                    importedSymbols.computeIfAbsent(path.toString(), s -> new ArrayList<>()).add(symbol.name);
                } else {
                    exportedSymbols.add(symbol.name);
                }
            }
        }

        // ========================= Try to resolve JBR symbols =========================
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, List<String>> file : importedSymbols.entrySet()) {
            System.out.print("Resolving symbols in " + file.getKey() + "...");
            List<String> unresolved = new ArrayList<>();
            for (String symbol : file.getValue()) {
                if (!exportedSymbols.contains(symbol)) {
                    unresolved.add(symbol);
                }
            }
            System.out.println(" " + (file.getValue().size() - unresolved.size()) + " resolved" +
                    (unresolved.isEmpty() ? "" : ", " + unresolved.size() + " failed"));
            if (!unresolved.isEmpty()) {
                errors.add(file.getKey() + ":\n" + String.join("\n", unresolved));
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("Failed to resolve symbols in " +
                    errors.size() + " files:\n" + String.join("\n\n", errors) + "\n");
        }
    }
}
