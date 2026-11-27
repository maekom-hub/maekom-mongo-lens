package com.maekom.mongolens.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class SpringYamlConfigParser {

    private data class MongoProps(
        var uri: String? = null,
        var host: String? = null,
        var port: Int? = null,
        var database: String? = null,
        var username: String? = null,
        var password: String? = null,
        var authDatabase: String? = null,
        var sslEnabled: Boolean? = null,
        var replicaSetName: String? = null
    ) {
        fun hasAny(): Boolean =
            uri != null || host != null || database != null || username != null || password != null ||
                port != null || authDatabase != null || sslEnabled != null || replicaSetName != null

        fun hasConnectionTarget(): Boolean =
            !uri.isNullOrBlank() || !host.isNullOrBlank() || !database.isNullOrBlank()

        fun mergedWith(override: MongoProps): MongoProps {
            return MongoProps(
                uri = override.uri ?: this.uri,
                host = override.host ?: this.host,
                port = override.port ?: this.port,
                database = override.database ?: this.database,
                username = override.username ?: this.username,
                password = override.password ?: this.password,
                authDatabase = override.authDatabase ?: this.authDatabase,
                sslEnabled = override.sslEnabled ?: this.sslEnabled,
                replicaSetName = override.replicaSetName ?: this.replicaSetName
            )
        }
    }

    private data class ParsedYamlDoc(
        val profiles: List<String>, // empty means base/default doc
        val props: MongoProps,
        val sourceFile: Path
    )

    fun scanProject(projectBasePath: Path?): List<MongoConnectionProfile> {
        if (projectBasePath == null || !Files.isDirectory(projectBasePath)) return emptyList()
        val envResolver = EnvResolver(projectBasePath)
        val yamlFiles = findSpringYamlFiles(projectBasePath)
        if (yamlFiles.isEmpty()) return emptyList()

        // Group YAML files by their resources directory (handles multi-module Spring Boot projects cleanly)
        val byResourcesDir = yamlFiles.groupBy { it.parent }
        val multipleModules = byResourcesDir.size > 1
        val result = mutableListOf<MongoConnectionProfile>()

        for ((resDir, files) in byResourcesDir.entries.sortedBy { it.key.toString() }) {
            val moduleName = deriveModuleName(projectBasePath, resDir)
            val baseFiles = files.filter { isBaseApplicationYaml(it.fileName.toString()) }
            val profileFiles = files.filter { !isBaseApplicationYaml(it.fileName.toString()) }

            var baseProps = MongoProps()
            var baseFileRef: Path? = null
            val profileDocs = linkedMapOf<String, MutableList<ParsedYamlDoc>>()

            // 1. Parse base application.yml / application.yaml (may contain multiple --- documents)
            for (bf in baseFiles.sortedBy { it.fileName.toString() }) {
                val docs = parseFileDocuments(bf, envResolver, forcedProfile = null)
                for (doc in docs) {
                    if (doc.profiles.isEmpty()) {
                        if (doc.props.hasAny()) {
                            baseProps = baseProps.mergedWith(doc.props)
                            if (baseFileRef == null) baseFileRef = bf
                        }
                    } else {
                        for (prof in doc.profiles) {
                            profileDocs.computeIfAbsent(prof) { mutableListOf() }.add(doc)
                        }
                    }
                }
            }

            // 2. Parse application-<profile>.yml / .yaml
            for (pf in profileFiles.sortedBy { it.fileName.toString() }) {
                val fileProfile = extractProfileFromFileName(pf.fileName.toString())
                val docs = parseFileDocuments(pf, envResolver, forcedProfile = fileProfile)
                for (doc in docs) {
                    val targets = if (doc.profiles.isEmpty()) listOfNotNull(fileProfile) else doc.profiles
                    for (prof in targets) {
                        profileDocs.computeIfAbsent(prof) { mutableListOf() }.add(doc)
                    }
                }
            }

            // 3. Emit base/default profile if it defines a Mongo target
            if (baseProps.hasConnectionTarget()) {
                result.add(
                    buildProfile(
                        profileName = "default",
                        props = baseProps,
                        sourceFile = baseFileRef ?: baseFiles.first(),
                        moduleName = if (multipleModules) moduleName else null
                    )
                )
            }

            // 4. Emit merged profile-specific configs
            for ((profName, docs) in profileDocs) {
                var merged = baseProps
                var lastFile: Path = docs.last().sourceFile
                for (d in docs) {
                    merged = merged.mergedWith(d.props)
                    lastFile = d.sourceFile
                }
                if (merged.hasConnectionTarget()) {
                    result.add(
                        buildProfile(
                            profileName = profName,
                            props = merged,
                            sourceFile = lastFile,
                            moduleName = if (multipleModules) moduleName else null
                        )
                    )
                }
            }
        }

        return result
    }

    private fun buildProfile(
        profileName: String,
        props: MongoProps,
        sourceFile: Path,
        moduleName: String?
    ): MongoConnectionProfile {
        val display = if (moduleName != null) "$moduleName:$profileName" else profileName
        val id = "yaml:${sourceFile.toAbsolutePath()}#$profileName"
        return MongoConnectionProfile(
            id = id,
            displayName = display,
            profileName = profileName,
            source = ProfileSource.YAML,
            uri = props.uri,
            host = props.host ?: "localhost",
            port = props.port ?: 27017,
            database = props.database,
            username = props.username,
            password = props.password,
            authDatabase = props.authDatabase,
            sourceFilePath = sourceFile.toAbsolutePath().toString(),
            moduleName = moduleName,
            readOnly = MongoConnectionProfile.isProductionProfile(profileName, sourceFile.toString()),
            tlsEnabled = props.sslEnabled == true,
            replicaSet = props.replicaSetName
        )
    }

    private fun parseFileDocuments(
        file: Path,
        envResolver: EnvResolver,
        forcedProfile: String?
    ): List<ParsedYamlDoc> {
        val docs = mutableListOf<ParsedYamlDoc>()
        try {
            val content = Files.readString(file, StandardCharsets.UTF_8)
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            for (rawDoc in yaml.loadAll(content)) {
                val rootMap = rawDoc as? Map<*, *> ?: continue
                val flat = flattenMap(rootMap)
                val docProfiles = extractProfiles(flat, envResolver)
                val effectiveProfiles = if (docProfiles.isNotEmpty()) docProfiles else listOfNotNull(forcedProfile)
                val mongoProps = extractMongoProps(flat, envResolver)
                docs.add(ParsedYamlDoc(effectiveProfiles, mongoProps, file))
            }
        } catch (_: Throwable) {
            // Skip malformed YAML gracefully without crashing UI
        }
        return docs
    }

    private fun flattenMap(map: Map<*, *>, prefix: String = ""): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in map) {
            val keyStr = k?.toString()?.trim() ?: continue
            val fullKey = if (prefix.isEmpty()) keyStr else "$prefix.$keyStr"
            if (v is Map<*, *>) {
                out.putAll(flattenMap(v, fullKey))
            } else {
                out[fullKey] = v
            }
        }
        return out
    }

    private fun extractProfiles(flat: Map<String, Any?>, envResolver: EnvResolver): List<String> {
        val raw = flat["spring.config.activate.on-profile"]
            ?: flat["spring.config.activate.onProfile"]
            ?: flat["spring.profiles"]
            ?: return emptyList()

        val rawList = when (raw) {
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            else -> raw.toString().split(",", "|")
        }

        return rawList
            .mapNotNull { envResolver.resolve(it)?.trim() }
            .map { it.removePrefix("!") }
            .filter { it.isNotEmpty() && !it.startsWith("(") }
            .distinct()
    }

    private fun extractMongoProps(flat: Map<String, Any?>, envResolver: EnvResolver): MongoProps {
        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = flat[k] ?: continue
                val resolved = envResolver.resolve(v.toString())?.trim()
                if (!resolved.isNullOrEmpty()) return resolved
            }
            return null
        }

        val portStr = str("spring.data.mongodb.port")
        val portInt = portStr?.toIntOrNull()

        val sslEnabledStr = str("spring.data.mongodb.ssl.enabled")
        val sslEnabledBool = sslEnabledStr?.let { it.equals("true", ignoreCase = true) }

        return MongoProps(
            uri = str("spring.data.mongodb.uri"),
            host = str("spring.data.mongodb.host"),
            port = portInt,
            database = str("spring.data.mongodb.database"),
            username = str("spring.data.mongodb.username"),
            password = str("spring.data.mongodb.password"),
            authDatabase = str(
                "spring.data.mongodb.authentication-database",
                "spring.data.mongodb.authenticationDatabase"
            ),
            sslEnabled = sslEnabledBool,
            replicaSetName = str(
                "spring.data.mongodb.replica-set-name",
                "spring.data.mongodb.replicaSetName"
            )
        )
    }

    private fun findSpringYamlFiles(projectBasePath: Path): List<Path> {
        val found = mutableListOf<Path>()
        val ignoredDirs = setOf(".git", ".gradle", ".idea", ".intellijPlatform", "build", "out", "target", "node_modules")

        try {
            Files.walkFileTree(projectBasePath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (dir != projectBasePath && name in ignoredDirs) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val normalized = file.toString().replace('\\', '/')
                    if (!normalized.contains("/src/main/resources/")) {
                        return FileVisitResult.CONTINUE
                    }
                    // Only directly under src/main/resources/ (or config/)
                    val afterRes = normalized.substringAfter("/src/main/resources/")
                    if (afterRes.contains("/")) {
                        return FileVisitResult.CONTINUE
                    }
                    val fileName = file.fileName.toString()
                    if (isSpringApplicationYaml(fileName)) {
                        found.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Throwable) {
            // Ignore walk errors
        }

        return found
    }

    private fun isSpringApplicationYaml(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower == "application.yml" ||
            lower == "application.yaml" ||
            (lower.startsWith("application-") && (lower.endsWith(".yml") || lower.endsWith(".yaml")))
    }

    private fun isBaseApplicationYaml(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower == "application.yml" || lower == "application.yaml"
    }

    private fun extractProfileFromFileName(fileName: String): String? {
        val lower = fileName.lowercase()
        if (!lower.startsWith("application-")) return null
        val noExt = fileName.substringBeforeLast('.')
        return noExt.removePrefix("application-").takeIf { it.isNotBlank() }
    }

    private fun deriveModuleName(projectRoot: Path, resourcesDir: Path): String {
        val rel = try {
            projectRoot.relativize(resourcesDir).toString().replace('\\', '/')
        } catch (_: Throwable) {
            return "root"
        }
        val beforeSrc = rel.substringBefore("src/main/resources").trim('/')
        return beforeSrc.ifEmpty { projectRoot.fileName?.toString() ?: "root" }
    }
}
