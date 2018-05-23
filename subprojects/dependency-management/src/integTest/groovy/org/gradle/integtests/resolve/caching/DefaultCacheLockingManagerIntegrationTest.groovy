/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.integtests.resolve.caching

import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.FileVisitor
import org.gradle.api.internal.file.collections.SingleIncludePatternFileTree
import org.gradle.cache.internal.FixedAgeOldestCacheCleanup
import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.maven.MavenModule

import static java.util.concurrent.TimeUnit.DAYS

class DefaultCacheLockingManagerIntegrationTest extends AbstractHttpDependencyResolutionTest {
    private final static long MAX_CACHE_AGE_IN_DAYS = FixedAgeOldestCacheCleanup.DEFAULT_MAX_AGE_IN_DAYS_FOR_EXTERNAL_CACHE_ENTRIES

    def repo = mavenHttpRepo
    def groupId = 'org.example'
    def artifactId = 'example'
    def snapshotModule = repo.module(groupId, artifactId, '1.0-SNAPSHOT').publish().allowAll()
    def releaseModule = repo.module(groupId, artifactId, '1.0').publish().allowAll()

    def setup() {
        requireOwnGradleUserHomeDir()
    }

    def "cleans up resources cache"() {
        given:
        buildscriptWithDependency(snapshotModule)

        when:
        succeeds 'resolve'

        then:
        def resources = findFiles(cacheDir, 'resources-*/*/*/maven-metadata.xml')
        resources.size() == 1

        when:
        buildscriptWithDependency(releaseModule)
        markForCleanup(gcFile)
        markForCleanup(resources[0].parentFile)

        and:
        succeeds 'resolve'

        then:
        resources[0].assertDoesNotExist()
    }

    private List<TestFile> findFiles(File baseDir, String includePattern) {
        List<TestFile> files = []
        new SingleIncludePatternFileTree(baseDir, includePattern).visit(new FileVisitor() {
            @Override
            void visitDir(FileVisitDetails dirDetails) {

            }

            @Override
            void visitFile(FileVisitDetails fileDetails) {
                files.add(new TestFile(fileDetails.file))
            }
        })
        return files;
    }

    private void buildscriptWithDependency(MavenModule module) {
        buildFile.text = """
            repositories {
                maven {
                    url = '${repo.uri}'
                }
            }
            configurations {
                custom
            }
            dependencies {
                custom group: '${module.groupId}', name: '${module.artifactId}', version: '${module.version}'
            }
            task resolve {
                doLast {
                    def artifacts = configurations.custom.incoming.artifacts
                    println "files: " + artifacts.artifactFiles.collect { it.name }
                }
            }
        """
    }

    TestFile getGcFile() {
        return cacheDir.file("gc.properties")
    }

    TestFile getCacheDir() {
        return executer.gradleUserHomeDir.file("caches", "modules-2")
    }

    void markForCleanup(File file) {
        file.lastModified = System.currentTimeMillis() - DAYS.toMillis(MAX_CACHE_AGE_IN_DAYS + 1)
    }
}
