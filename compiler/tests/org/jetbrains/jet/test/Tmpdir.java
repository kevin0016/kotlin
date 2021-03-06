/*
 * Copyright 2010-2012 JetBrains s.r.o.
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

package org.jetbrains.jet.test;

import com.google.common.io.Files;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.JetTestUtils;
import org.jetbrains.jet.utils.ExceptionUtils;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.File;
import java.io.IOException;

/**
 * @author Stepan Koltsov
 */
public class Tmpdir extends TestWatcher {

    private File tmpDir;

    @Override
    protected void starting(Description description) {
        try {
            tmpDir = Files.createTempDir().getCanonicalFile();
        }
        catch (IOException e) {
            throw ExceptionUtils.rethrow(e);
        }
    }

    @Override
    protected void succeeded(Description description) {
        JetTestUtils.rmrf(tmpDir);
    }

    @Override
    protected void failed(Throwable e, Description description) {
        System.err.println("Temp directory: " + tmpDir);
    }

    @NotNull
    public File getTmpDir() {
        return tmpDir;
    }
}
