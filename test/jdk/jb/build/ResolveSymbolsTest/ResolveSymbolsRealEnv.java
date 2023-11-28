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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * @test
 * @summary ResolveSymbolsTest verifies that the set of imported symbols is
 * limited by the predefined symbols list.
 * @requires (os.family = = " linux ")
 * @run main ResolveSymbolsTest
 */

public class ResolveSymbolsRealEnv extends ResolveSymbolsTestBase {
    List<Path> getDependencies(Path path) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("ldd " + path);
        List<Path> result = new BufferedReader(new InputStreamReader(process.getInputStream()))
                .lines()
                .map(s -> {
                    // parse expressions like "libc.so.6 => /lib/x86_64-linux-gnu/libc.so.6 (0x00007f931b000000)"
                    String[] columns = s.split("=>");
                    return columns.length >= 2 ? columns[1].strip().split("\\s+")[0] : null;
                })
                .filter(Objects::nonNull)
                .map(Paths::get)
                .toList();

        if (process.waitFor() != 0) {
            return null;
        }

        return result;
    }

    public List<String> getExternalSymbols() throws IOException {
        return getJbrBinaries().stream()
                .flatMap(path -> {
                    try {
                        return getDependencies(path).stream();
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
                .distinct()
                .map(path -> {
                    try {
                        return runReadElf(path);
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(Objects::nonNull)
                .flatMap(s -> parseElf(s).stream())
                .filter(elfSymbol -> symbolsFilter(elfSymbol, ""))
                .filter(elfSymbol -> !elfSymbol.sectionNumber.equals("UND"))
                .map(elfSymbol -> elfSymbol.name)
                .toList();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        new ResolveSymbolsRealEnv().doTest();
    }
}
