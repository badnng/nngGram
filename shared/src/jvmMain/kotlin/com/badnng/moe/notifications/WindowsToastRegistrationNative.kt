package com.badnng.moe.notifications

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Properties
import java.util.UUID

/** Lazy native boundary. Merely constructing settings never loads COM or changes OS integration. */
internal class NativeWindowsToastRegistrationBoundary(
    private val directory: Path,
    private val activationEntryPointAvailable: Boolean,
    private val native: WindowsToastRegistrationNativeDriver = WindowsToastRegistrationNativeDriver(),
) : WindowsToastRegistrationBoundary {
    private var locked = false

    override fun readEnvironment(): WindowsToastRegistrationEnvironment {
        val nativeRuntime = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime" &&
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        if (!nativeRuntime) return WindowsToastRegistrationEnvironment(false, activationEntryPointAvailable, null, false, null)
        val executable = native.currentExecutable().toRealPath()
        val programs = native.programsDirectory().toRealPath()
        return WindowsToastRegistrationEnvironment(true, activationEntryPointAvailable, executable,
            Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS), programs)
    }

    override fun <T> exclusive(block: () -> T): T {
        check(!locked)
        Files.createDirectories(directory)
        // This lock is local app data, never a shared Start Menu/registry lock or a notification.
        return FileChannel.open(directory.resolve("windows-toast-registration.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                locked = true
                try { block() } finally { locked = false }
            }
        }
    }

    override fun readManifest(identity: WindowsToastRegistrationIdentity): WindowsToastRegistrationManifest? {
        val file = manifestPath(identity)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_SHORTCUT_BYTES * 2L) throw InvalidToastManifest()
        val properties = Properties().apply { Files.newInputStream(file).use { load(it) } }
        try {
            if (properties.stringPropertyNames() != MANIFEST_KEYS || properties.getProperty("schema") != "1") throw InvalidToastManifest()
            val stored = WindowsToastRegistrationManifest(
                WindowsToastRegistrationIdentity(properties.getProperty("aumid"), properties.getProperty("clsid"),
                    Path.of(properties.getProperty("executable")), Path.of(properties.getProperty("shortcut"))),
                properties.getProperty("nonce"), WindowsToastManifestStage.valueOf(properties.getProperty("stage")),
                properties.getProperty("shortcutBase64"), properties.getProperty("shortcutSha256"),
            )
            validateManifest(identity, stored)
            return stored
        } catch (_: Exception) { throw InvalidToastManifest() }
    }

    override fun writeManifest(expected: WindowsToastRegistrationManifest?, next: WindowsToastRegistrationManifest?) {
        check(locked)
        val identity = (next ?: expected ?: error("No manifest identity")).identity
        check(readManifest(identity) == expected)
        val destination = manifestPath(identity)
        if (next == null) {
            Files.delete(destination)
            return
        }
        validateManifest(identity, next)
        val properties = Properties().apply {
            setProperty("schema", "1")
            setProperty("aumid", identity.aumid)
            setProperty("clsid", identity.clsid)
            setProperty("executable", identity.executable.toString())
            setProperty("shortcut", identity.shortcut.toString())
            setProperty("nonce", next.nonce)
            setProperty("stage", next.stage.name)
            setProperty("shortcutBase64", next.shortcutBase64)
            setProperty("shortcutSha256", next.shortcutSha256)
        }
        val temporary = Files.createTempFile(directory, "toast-manifest-", ".tmp")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, "nngGram owned toast registration; no account content") }
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            // Refuse the transaction if atomic commit is unavailable; do not fall back to truncation.
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    override fun readRegistry(identity: WindowsToastRegistrationIdentity) = native.readRegistry(registryPath(identity))

    override fun createRegistry(identity: WindowsToastRegistrationIdentity, expected: WindowsToastRegistrySnapshot): Boolean {
        check(locked)
        return native.createRegistry(registryPath(identity), expected)
    }

    override fun deleteRegistry(identity: WindowsToastRegistrationIdentity, expected: WindowsToastRegistrySnapshot): Boolean {
        check(locked)
        return native.deleteRegistry(registryPath(identity), expected)
    }

    override fun readShortcut(path: Path): WindowsToastShortcutSnapshot? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..MAX_SHORTCUT_BYTES.toLong())
            return WindowsToastShortcutSnapshot(FOREIGN_SHORTCUT, "")
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val bytes = Files.readAllBytes(path)
        val spec = runCatching { native.readShortcut(path) }.getOrElse { return WindowsToastShortcutSnapshot(FOREIGN_SHORTCUT, sha256(bytes)) }
        val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime())
        return WindowsToastShortcutSnapshot(spec, sha256(bytes))
    }

    override fun renderShortcut(spec: WindowsToastShortcutSpec): ByteArray {
        check(locked)
        val temporary = Files.createTempFile(directory, "toast-link-", ".lnk")
        try {
            native.writeShortcut(temporary, spec)
            check(native.readShortcut(temporary) == spec)
            check(Files.size(temporary) in 1..MAX_SHORTCUT_BYTES.toLong())
            return Files.readAllBytes(temporary)
        } finally { Files.deleteIfExists(temporary) }
    }

    override fun createShortcut(path: Path, spec: WindowsToastShortcutSpec, bytes: ByteArray): Boolean {
        check(locked && bytes.size in 1..MAX_SHORTCUT_BYTES)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        // Validate recovered journal bytes with ShellLink before copying them to the public location.
        val temporary = Files.createTempFile(directory, "toast-verify-", ".lnk")
        try {
            Files.write(temporary, bytes)
            check(native.readShortcut(temporary) == spec)
        } finally { Files.deleteIfExists(temporary) }
        return native.writeFileExclusive(path, bytes)
    }

    override fun deleteShortcut(path: Path, expected: WindowsToastShortcutSnapshot): Boolean {
        check(locked)
        if (readShortcut(path) != expected) return false
        // The native handle excludes write/delete sharing, hashes that same open file and marks
        // that handle for deletion. It cannot accidentally delete a replacement by its path.
        return native.deleteFileIfHashMatches(path, expected.sha256)
    }

    private fun manifestPath(identity: WindowsToastRegistrationIdentity): Path = directory.resolve("toast-${identity.clsid}.properties")
    private fun registryPath(identity: WindowsToastRegistrationIdentity) = "Software\\Classes\\CLSID\\{${identity.clsid}}"
}

private val MANIFEST_KEYS = setOf("schema", "aumid", "clsid", "executable", "shortcut", "nonce", "stage", "shortcutBase64", "shortcutSha256")
private val FOREIGN_SHORTCUT = WindowsToastShortcutSpec("", "", "", "", "", "")

/** Minimal Windows x64 ABI, read from SDK 10.0.26100.0. No AWT, scripts or launcher processes. */
internal open class WindowsToastRegistrationNativeDriver {
    private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
    private val shell by lazy { NativeLibrary.getInstance("shell32") }
    private val ole by lazy { NativeLibrary.getInstance("ole32") }
    private val props by lazy { NativeLibrary.getInstance("ole32") }
    private val registry by lazy { NativeLibrary.getInstance("advapi32") }
    private val transactions by lazy { NativeLibrary.getInstance("ktmw32") }

    open fun currentExecutable(): Path = Memory(65_536).use { buffer ->
        val count = kernel.getFunction("GetModuleFileNameW").invokeInt(arrayOf(null, buffer, 32_768))
        check(count in 1 until 32_768)
        Path.of(buffer.getWideString(0))
    }

    open fun programsDirectory(): Path = guidMemory("a77f5d77-2e2b-44c3-a6a2-aba601054a51").use { id ->
        Memory(8).use { result ->
            checkHr(shell.getFunction("SHGetKnownFolderPath").invokeInt(arrayOf(id, 0x4000, null, result)))
            val pointer = checkNotNull(result.getPointer(0))
            try { Path.of(pointer.getWideString(0)) } finally { ole.getFunction("CoTaskMemFree").invokeVoid(arrayOf(pointer)) }
        }
    }

    open fun writeFileExclusive(path: Path, bytes: ByteArray): Boolean {
        require(bytes.size in 1..MAX_SHORTCUT_BYTES)
        val handle = kernel.getFunction("CreateFileW").invokePointer(arrayOf(
            WString(path.toString()), 0x4001_0000, 0, null, 1, 0x80, null, // GENERIC_WRITE|DELETE, CREATE_NEW
        ))
        if (Pointer.nativeValue(handle) == -1L) {
            val code = kernel.getFunction("GetLastError").invokeInt(emptyArray())
            if (code == 80 || code == 183) return false
            error("Exclusive shortcut create failed")
        }
        checkNotNull(handle)
        try {
            Memory(bytes.size.toLong()).use { source -> Memory(4).use { written ->
                source.write(0, bytes, 0, bytes.size)
                check(kernel.getFunction("WriteFile").invokeInt(arrayOf(handle, source, bytes.size, written, null)) != 0)
                check(written.getInt(0) == bytes.size)
                check(kernel.getFunction("FlushFileBuffers").invokeInt(arrayOf(handle)) != 0)
            } }
            return true
        } catch (failure: Exception) {
            // Only this CREATE_NEW handle is deleted, even when a short write has no parseable link.
            runCatching { markFileForDeletion(handle) }
            throw failure
        } finally { check(kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) != 0) }
    }

    open fun deleteFileIfHashMatches(path: Path, expectedHash: String): Boolean {
        val handle = kernel.getFunction("CreateFileW").invokePointer(arrayOf(
            WString(path.toString()), 0x8001_0000.toInt(), 1, null, 3, 0x0020_0000, null,
            // GENERIC_READ|DELETE, FILE_SHARE_READ only, OPEN_EXISTING, OPEN_REPARSE_POINT
        ))
        check(handle != null && Pointer.nativeValue(handle) != -1L)
        try {
            Memory(8).use { size ->
                check(kernel.getFunction("GetFileSizeEx").invokeInt(arrayOf(handle, size)) != 0)
                val length = size.getLong(0)
                if (length !in 1..MAX_SHORTCUT_BYTES.toLong()) return false
                Memory(length).use { data -> Memory(4).use { read ->
                    check(kernel.getFunction("ReadFile").invokeInt(arrayOf(handle, data, length.toInt(), read, null)) != 0)
                    if (read.getInt(0) != length.toInt() || sha256(data.getByteArray(0, length.toInt())) != expectedHash) return false
                } }
            }
            markFileForDeletion(handle)
            return true
        } finally { check(kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) != 0) }
    }

    private fun markFileForDeletion(handle: Pointer) = Memory(1).use { disposition ->
        disposition.setByte(0, 1)
        check(kernel.getFunction("SetFileInformationByHandle").invokeInt(arrayOf(handle, 4, disposition, 1)) != 0)
    }

    open fun readShortcut(path: Path): WindowsToastShortcutSpec = apartment {
        shellLink { link ->
            query(link, "0000010b-0000-0000-c000-000000000046").useCom { persist ->
                checkHr(call(persist, 5, WString(path.toString()), 0)) // IPersistFile::Load(STGM_READ)
            }
            val executable = linkString(link, 3, path = true)
            val arguments = linkString(link, 10)
            val workingDirectory = linkString(link, 8)
            val description = linkString(link, 6)
            query(link, "886d8eeb-8cf2-4446-8d02-cdba1dbdcf99").useCom { store ->
                WindowsToastShortcutSpec(executable, arguments, workingDirectory, description,
                    getProperty(store, 5, 31), getProperty(store, 26, 72).lowercase())
            }
        }
    }

    open fun writeShortcut(path: Path, spec: WindowsToastShortcutSpec) = apartment {
        shellLink { link ->
            checkHr(call(link, 20, WString(spec.executable)))
            checkHr(call(link, 11, WString(spec.arguments)))
            checkHr(call(link, 9, WString(spec.workingDirectory)))
            checkHr(call(link, 7, WString(spec.description)))
            checkHr(call(link, 15, 1)) // SW_SHOWNORMAL, never a shell command.
            query(link, "886d8eeb-8cf2-4446-8d02-cdba1dbdcf99").useCom { store ->
                setProperty(store, 5, 31, spec.aumid)
                setProperty(store, 26, 72, spec.clsid)
                checkHr(call(store, 7)) // IPropertyStore::Commit
            }
            query(link, "0000010b-0000-0000-c000-000000000046").useCom { persist ->
                checkHr(call(persist, 6, WString(path.toString()), 1)) // IPersistFile::Save
            }
        }
    }

    open fun readRegistry(path: String): WindowsToastRegistrySnapshot? = readRegistryWithin(path, null)

    private fun readRegistryWithin(path: String, transaction: Pointer?): WindowsToastRegistrySnapshot? {
        val root = openKey(path, KEY_READ, transaction) ?: return null
        try {
            val keys = linkedMapOf<String, Map<String, WindowsToastRegistryValue>>()
            readTree(root, "", keys, 0, transaction)
            return WindowsToastRegistrySnapshot(keys)
        } finally { closeKey(root) }
    }

    open fun createRegistry(path: String, expected: WindowsToastRegistrySnapshot): Boolean = registryTransaction { transaction ->
        val root = createKey(path, transaction) ?: return@registryTransaction false
        try {
            expected.keys.getValue("").forEach { (name, value) -> setValue(root, name, value) }
            expected.keys.filterKeys { it.isNotEmpty() }.forEach { (child, values) ->
                require(!child.contains('\\'))
                val key = createKey("$path\\$child", transaction) ?: error("Registry child already exists")
                try { values.forEach { (name, value) -> setValue(key, name, value) } } finally { closeKey(key) }
            }
            check(readRegistryWithin(path, transaction) == expected)
            true
        } finally { closeKey(root) }
    }

    open fun deleteRegistry(path: String, expected: WindowsToastRegistrySnapshot): Boolean = registryTransaction { transaction ->
        if (readRegistryWithin(path, transaction) != expected) return@registryTransaction false
        // The snapshot/leaf deletes commit in one TxR transaction. A concurrent non-transacted
        // writer invalidates the transaction; there is no compare-then-delete race on foreign data.
        expected.keys.entries.sortedByDescending { it.key.length }.forEach { (relative, values) ->
            val target = if (relative.isEmpty()) path else "$path\\$relative"
            val key = openKey(target, KEY_MUTATE, transaction) ?: return@registryTransaction false
            try {
                if (readValues(key) != values || childNames(key).isNotEmpty()) return@registryTransaction false
                values.forEach { (name, value) ->
                    if (readValues(key)[name] != value) return@registryTransaction false
                    checkWin32(registry.getFunction("RegDeleteValueW").invokeInt(arrayOf(key, WString(name))))
                }
                if (readValues(key).isNotEmpty() || childNames(key).isNotEmpty()) return@registryTransaction false
            } finally { closeKey(key) }
            checkWin32(registry.getFunction("RegDeleteKeyTransactedW").invokeInt(arrayOf(HKCU, WString(target), KEY_WOW64_64KEY, 0, transaction, null)))
        }
        true
    }

    private fun registryTransaction(block: (Pointer) -> Boolean): Boolean {
        val transaction = transactions.getFunction("CreateTransaction").invokePointer(arrayOf(
            null, null, 0, 0, 0, 10_000, WString("nngGram owned toast registration"),
        ))
        check(transaction != null && Pointer.nativeValue(transaction) != -1L)
        var committed = false
        try {
            if (!block(transaction)) return false
            check(transactions.getFunction("CommitTransaction").invokeInt(arrayOf(transaction)) != 0)
            committed = true
            return true
        } finally {
            if (!committed) runCatching { transactions.getFunction("RollbackTransaction").invokeInt(arrayOf(transaction)) }
            check(kernel.getFunction("CloseHandle").invokeInt(arrayOf(transaction)) != 0)
        }
    }

    private fun openKey(path: String, access: Int, transaction: Pointer?): Pointer? = Memory(8).use { out ->
        val result = if (transaction == null)
            registry.getFunction("RegOpenKeyExW").invokeInt(arrayOf(HKCU, WString(path), REG_OPTION_OPEN_LINK, access or KEY_WOW64_64KEY, out))
        else registry.getFunction("RegOpenKeyTransactedW").invokeInt(arrayOf(HKCU, WString(path), REG_OPTION_OPEN_LINK, access or KEY_WOW64_64KEY, out, transaction, null))
        if (result == 2) null else { checkWin32(result); checkNotNull(out.getPointer(0)) }
    }

    private fun createKey(path: String, transaction: Pointer): Pointer? = Memory(8).use { out ->
        Memory(4).use { disposition ->
            checkWin32(registry.getFunction("RegCreateKeyTransactedW").invokeInt(arrayOf(
                HKCU, WString(path), 0, null, 0, KEY_MUTATE or KEY_WOW64_64KEY, null, out, disposition, transaction, null,
            )))
            val key = checkNotNull(out.getPointer(0))
            if (disposition.getInt(0) != 1) { closeKey(key); null } else key
        }
    }

    private fun readTree(key: Pointer, relative: String, output: MutableMap<String, Map<String, WindowsToastRegistryValue>>, depth: Int, transaction: Pointer?) {
        check(depth <= 4 && output.size < 16)
        output[relative] = readValues(key)
        for (child in childNames(key)) {
            Memory(8).use { result ->
                val code = if (transaction == null)
                    registry.getFunction("RegOpenKeyExW").invokeInt(arrayOf(key, WString(child), REG_OPTION_OPEN_LINK, KEY_READ or KEY_WOW64_64KEY, result))
                else registry.getFunction("RegOpenKeyTransactedW").invokeInt(arrayOf(key, WString(child), REG_OPTION_OPEN_LINK, KEY_READ or KEY_WOW64_64KEY, result, transaction, null))
                checkWin32(code)
                val nested = checkNotNull(result.getPointer(0))
                try { readTree(nested, if (relative.isEmpty()) child else "$relative\\$child", output, depth + 1, transaction) } finally { closeKey(nested) }
            }
        }
    }

    private fun readValues(key: Pointer): Map<String, WindowsToastRegistryValue> {
        val output = linkedMapOf<String, WindowsToastRegistryValue>()
        Memory(1024).use { name -> Memory(4).use { nameLength -> Memory(4).use { type ->
            Memory(65_536).use { data -> Memory(4).use { size ->
                for (index in 0..32) {
                    name.clear(); nameLength.setInt(0, 512); size.setInt(0, 65_536)
                    val result = registry.getFunction("RegEnumValueW").invokeInt(arrayOf(key, index, name, nameLength, null, type, data, size))
                    if (result == 259) return output
                    checkWin32(result)
                    check(index < 32 && nameLength.getInt(0) < 512 && size.getInt(0) in 0..65_536)
                    output[name.getWideString(0)] = WindowsToastRegistryValue(type.getInt(0),
                        Base64.getEncoder().encodeToString(data.getByteArray(0, size.getInt(0))))
                }
            } }
        } } }
        error("Registry value bound")
    }

    private fun childNames(key: Pointer): List<String> {
        val output = mutableListOf<String>()
        Memory(1024).use { name -> Memory(4).use { length ->
            for (index in 0..16) {
                name.clear(); length.setInt(0, 512)
                val result = registry.getFunction("RegEnumKeyExW").invokeInt(arrayOf(key, index, name, length, null, null, null, null))
                if (result == 259) return output
                checkWin32(result)
                check(index < 16 && length.getInt(0) < 512)
                output += name.getWideString(0)
            }
        } }
        error("Registry child bound")
    }

    private fun setValue(key: Pointer, name: String, value: WindowsToastRegistryValue) {
        val data = Base64.getDecoder().decode(value.base64)
        require(data.size <= 65_536)
        Memory(maxOf(1, data.size).toLong()).use { memory ->
            memory.write(0, data, 0, data.size)
            checkWin32(registry.getFunction("RegSetValueExW").invokeInt(arrayOf(key, WString(name), 0, value.type, memory, data.size)))
        }
    }

    private fun closeKey(key: Pointer) { checkWin32(registry.getFunction("RegCloseKey").invokeInt(arrayOf(key))) }

    private fun <T> apartment(block: () -> T): T {
        val result = ole.getFunction("CoInitializeEx").invokeInt(arrayOf(null, 0)) // COINIT_MULTITHREADED
        checkHr(result) // RPC_E_CHANGED_MODE does not acquire a reference and must not uninitialize.
        return try { block() } finally { ole.getFunction("CoUninitialize").invokeVoid(emptyArray()) }
    }

    private fun <T> shellLink(block: (Pointer) -> T): T =
        guidMemory("00021401-0000-0000-c000-000000000046").use { clsid ->
            guidMemory("000214f9-0000-0000-c000-000000000046").use { iid ->
                Memory(8).use { out ->
                    checkHr(ole.getFunction("CoCreateInstance").invokeInt(arrayOf(clsid, null, 1, iid, out)))
                    checkNotNull(out.getPointer(0)).useCom(block)
                }
            }
        }

    private fun query(pointer: Pointer, iid: String): Pointer = guidMemory(iid).use { id ->
        Memory(8).use { result -> checkHr(call(pointer, 0, id, result)); checkNotNull(result.getPointer(0)) }
    }

    private fun linkString(link: Pointer, slot: Int, path: Boolean = false): String = Memory(65_536).use { buffer ->
        buffer.clear()
        checkHr(if (path) call(link, slot, buffer, 32_768, null, 4) else call(link, slot, buffer, 32_768))
        buffer.getWideString(0).also { check(it.length < 32_767) }
    }

    private fun getProperty(store: Pointer, pid: Int, vt: Int): String = propertyKey(pid).use { key ->
        Memory(24).use { variant ->
            variant.clear()
            try {
                checkHr(call(store, 5, key, variant))
                check(variant.getShort(0).toInt() == vt)
                val value = checkNotNull(variant.getPointer(8))
                if (vt == 31) value.getWideString(0) else guidString(value)
            } finally { checkHr(props.getFunction("PropVariantClear").invokeInt(arrayOf(variant))) }
        }
    }

    private fun setProperty(store: Pointer, pid: Int, vt: Int, text: String) = propertyKey(pid).use { key ->
        val value = if (vt == 72) guidMemory(text) else Memory((text.length + 1L) * 2).apply { setWideString(0, text) }
        value.use { memory -> Memory(24).use { variant ->
            variant.clear(); variant.setShort(0, vt.toShort()); variant.setPointer(8, memory)
            // Input storage is borrowed JNA Memory; PropVariantClear must not free it.
            checkHr(call(store, 6, key, variant))
        } }
    }

    private fun propertyKey(pid: Int): Memory = Memory(20).apply {
        guidMemory("9f4c2855-9f79-4b39-a8d0-e1d42de1d5f3").use { write(0, it.getByteArray(0, 16), 0, 16) }
        setInt(16, pid)
    }
}

internal fun guidMemory(text: String): Memory {
    val uuid = UUID.fromString(text)
    return Memory(16).apply {
        setInt(0, (uuid.mostSignificantBits ushr 32).toInt())
        setShort(4, (uuid.mostSignificantBits ushr 16).toShort())
        setShort(6, uuid.mostSignificantBits.toShort())
        for (index in 0..7) setByte(8L + index, (uuid.leastSignificantBits ushr (56 - index * 8)).toByte())
    }
}

private fun guidString(pointer: Pointer): String {
    val high = ((pointer.getInt(0).toLong() and 0xffff_ffffL) shl 32) or
        ((pointer.getShort(4).toLong() and 0xffffL) shl 16) or (pointer.getShort(6).toLong() and 0xffffL)
    var low = 0L
    for (index in 0..7) low = (low shl 8) or (pointer.getByte(8L + index).toLong() and 255)
    return UUID(high, low).toString()
}

private fun <T> Pointer.useCom(block: (Pointer) -> T): T = try { block(this) } finally { call(this, 2) }
private fun call(pointer: Pointer, slot: Int, vararg args: Any?): Int =
    Function.getFunction(pointer.getPointer(0).getPointer(slot * 8L), Function.ALT_CONVENTION).invokeInt(arrayOf(pointer, *args))
private fun checkHr(value: Int) { check(value >= 0) { "Windows COM operation failed" } }
private fun checkWin32(value: Int) { check(value == 0) { "Windows registration operation failed" } }
private const val KEY_READ = 0x20019
private const val KEY_MUTATE = 0x3001f // READ_CONTROL|DELETE|query/set/create/enumerate/notify
private const val KEY_WOW64_64KEY = 0x100
private const val REG_OPTION_OPEN_LINK = 8 // Compare a REG_LINK itself; never follow a foreign key.
private val HKCU = Pointer.createConstant(-2_147_483_647L)
