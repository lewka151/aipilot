package com.aipilot;

import com.aipilot.core.AgentLoop;
import com.aipilot.core.AnswerSanitizer;
import com.aipilot.core.ChatTitles;
import com.aipilot.core.ChatUrls;
import com.aipilot.core.CodeEditorService;
import com.aipilot.core.CodeRunService;
import com.aipilot.core.DeviceBinding;
import com.aipilot.core.FileIndexManager;
import com.aipilot.core.FileOps;
import com.aipilot.core.HashCommands;
import com.aipilot.core.ProjectAliases;
import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;
import com.aipilot.core.model.ProjectServer;
import com.aipilot.core.model.SafetyVerdict;
import com.aipilot.infra.api.ApiJson;
import com.aipilot.infra.api.BridgeJob;
import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.WindowsAutostart;
import com.aipilot.infra.update.UpdateService;
import com.aipilot.ui.AutoUpdateScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CoreUnitTest {
    @TempDir
    Path tmp;

    @Test
    void linuxDesktopPropertiesForceGtk3AndSoftwarePrismFallback() {
        var props = DesktopLaunchSupport.linuxProperties();
        assertEquals("3", props.get("jdk.gtk.version"));
        assertEquals("es2,sw", props.get("prism.order"));
        assertEquals("XToolkit", props.get("awt.toolkit.name"));
        assertTrue(DesktopLaunchSupport.missingJavaFxMessage().contains("JavaFX"));
    }

    @Test
    void windowsDesktopPropertiesKeepUtf8AndPrismLcdtext() {
        var props = DesktopLaunchSupport.windowsProperties();
        assertEquals("UTF-8", props.get("file.encoding"));
        assertEquals("false", props.get("prism.lcdtext"));
        if (DesktopLaunchSupport.isWindows()) {
            assertTrue(DesktopLaunchSupport.missingJavaFxMessage().contains("run.bat"));
        }
    }

    @Test
    void updateDecisionIgnoresMissingLocalHashOnSameVersion() {
        var rel = new UpdateService.ReleaseInfo("1.0.0", "abc12345", null, 0, null, null, null);
        var d = AutoUpdateScheduler.evaluate(rel, null, "1.0.0");
        assertFalse(d.needUpdate());
    }

    @Test
    void updateDecisionDetectsNewerVersion() {
        var rel = new UpdateService.ReleaseInfo("2.0.0", "deadbeef", null, 0, null, null, null);
        var d = AutoUpdateScheduler.evaluate(rel, "abc", "1.0.0");
        assertTrue(d.needUpdate());
    }

    @Test
    void compareVersionsWorks() {
        assertTrue(UpdateService.isNewerVersion("1.2.0", "1.1.9"));
        assertFalse(UpdateService.isNewerHash("abc", null));
    }

    @Test
    void parseManifestUsesAipilotVersionNotBunkerVersion() throws Exception {
        String json = """
                {"ok":true,"data":{
                  "version":"9.9.9",
                  "aipilot_version":"0.1.1",
                  "aipilot_build_hash":"abcd1234",
                  "aipilot_installer_url":"/downloads/AIPilot-Setup.exe",
                  "builds":{"aipilot":{"version":"0.1.1","hash":"abcd1234"}}
                }}
                """;
        var rel = UpdateService.parseManifest(json, "https://example.test");
        assertEquals("0.1.1", rel.version());
        assertEquals("abcd1234", rel.buildHash());
        assertEquals("https://example.test/downloads/AIPilot-Setup.exe", rel.installerUrl());
    }

    @Test
    void parseManifestIgnoresTopLevelVersionWhenAipilotMissing() throws Exception {
        String json = """
                {"version":"3.2.1","builds":{"aipilot":{"version":"0.1.1","hash":"ffffeeee"}}}
                """;
        var rel = UpdateService.parseManifest(json, "https://example.test");
        assertEquals("0.1.1", rel.version());
        assertEquals("ffffeeee", rel.buildHash());
    }

    @Test
    void updateDecisionMatchingOverlayHashIsCurrent() {
        var rel = new UpdateService.ReleaseInfo("0.1.1", "abc12345", null, 0, null, null, null);
        var d = AutoUpdateScheduler.evaluate(rel, "abc12345", "0.1.1");
        assertFalse(d.needUpdate());
        assertFalse(AutoUpdateScheduler.shouldCacheRemoteHash(d, "abc12345", "0.1.1", "0.1.1"));
    }

    @Test
    void updateDecisionDetectsSameVersionDifferentHash() {
        var rel = new UpdateService.ReleaseInfo("0.1.1", "deadbeef", null, 0, null, null, null);
        var d = AutoUpdateScheduler.evaluate(rel, "abc12345", "0.1.1");
        assertTrue(d.needUpdate());
    }

    @Test
    void updateDecisionLocalNewerDoesNotCacheRemoteHash() {
        var rel = new UpdateService.ReleaseInfo("0.1.0", "oldhash1", null, 0, null, null, null);
        var d = AutoUpdateScheduler.evaluate(rel, null, "0.1.1");
        assertFalse(d.needUpdate());
        assertFalse(AutoUpdateScheduler.shouldCacheRemoteHash(d, null, "0.1.1", "0.1.0"));
    }

    @Test
    void safetyVerdictParsesFencedJson() {
        SafetyVerdict v = SafetyVerdict.parse("```json\n{\"safe\": false, \"reason\": \"rm\"}\n```");
        assertFalse(v.safe());
        assertEquals("rm", v.reason());
    }

    @Test
    void apiJsonUnwrapsEnvelopeAndFormatsErrors() throws Exception {
        var data = ApiJson.parse("{\"ok\":true,\"data\":{\"qwen\":{\"ready\":true}}}");
        assertTrue(data.path("qwen").path("ready").asBoolean());
        assertEquals("id-1", ApiJson.parse("{\"id\":\"id-1\"}").path("id").asText());
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> ApiJson.parse("{\"ok\":false,\"error\":\"nope\"}"));
        assertEquals("nope", failed.getMessage());
        assertEquals("HTTP 500: boom", ApiJson.httpError(500, "boom"));
        assertTrue(ApiJson.httpError(404, "<html>not json</html>").contains("HTTP 404"));
    }

    @Test
    void projectAliasesParseDisplayNameAndPaths() {
        var parsed = ProjectAliases.parse(
                "Проект 1, h:/1/s = проект s, h:/1/f = проект f, C:/Games/Saves/f = проект П",
                "folder");
        assertEquals("Проект 1", parsed.displayName);
        assertEquals(4, parsed.paths.size());
        assertEquals(".", parsed.paths.get(0).path);
        assertEquals("h:/1/s", parsed.paths.get(1).path);
        assertEquals("проект s", parsed.paths.get(1).name);
        Project round = new Project();
        round.displayName = parsed.displayName;
        round.paths = parsed.paths;
        var again = ProjectAliases.parse(ProjectAliases.format(round), "folder");
        assertEquals("Проект 1", again.displayName);
        assertTrue(again.paths.stream().anyMatch(p -> "h:/1/s".equals(p.path) && "проект s".equals(p.name)));
    }

    @Test
    void extraFolderIndexAndFileWriteUseAliasPrefix() throws Exception {
        Path root = tmp.resolve("proj");
        Path extra = tmp.resolve("lib");
        Files.createDirectories(root);
        Files.createDirectories(extra);
        Files.writeString(extra.resolve("util.py"), "old", StandardCharsets.UTF_8);
        Project p = new Project();
        p.root = root.toString();
        p.paths.add(new ProjectPath(".", "root"));
        var extraPath = new ProjectPath(extra.toString(), "lib");
        extraPath.name = "lib";
        p.paths.add(extraPath);
        String index = new FileIndexManager().rescanPreservingDescriptions(p);
        assertTrue(index.contains("lib/util.py"));
        var props = new CodeEditorService().parseProposals("""
                ```xml
                <file path="lib/util.py">
                new
                </file>
                ```
                """, p);
        assertEquals(1, props.size());
        new CodeEditorService().apply(props.getFirst(), p);
        assertEquals("new", Files.readString(extra.resolve("util.py")).trim());
        assertTrue(new CodeEditorService().parseProposals("""
                ```xml
                <file path="C:/Windows/x.py">
                x
                </file>
                ```
                """, p).isEmpty());
        assertTrue(new CodeEditorService().parseProposals("""
                ```xml
                <file path="../secret.py">
                x
                </file>
                ```
                """, p).isEmpty());
    }

    @Test
    void codeRunParserAcceptsPythonAndRejectsDanger() {
        var blocks = CodeRunService.parse("""
                ```python
                print("hi")
                ```
                ```powershell
                Get-ChildItem
                ```
                """);
        assertEquals(2, blocks.size());
        assertEquals("python", blocks.get(0).language());
        assertEquals("powershell", blocks.get(1).language());
        assertTrue(CodeRunService.denyReason("rm -rf /").contains("опасн"));
        assertTrue(CodeRunService.denyReason("..\\secret.py").contains("опасн"));
        assertTrue(CodeRunService.denyReason("print(1)").isBlank());
        var job = new BridgeJob("1", "qwen", "p", "n", "q", "Title Only", false);
        assertEquals("", job.chatIdOrEmpty());
        assertNotEquals("Title Only", job.chatIdOrEmpty());
        assertEquals("", job.projectIdOrEmpty());
    }

    @Test
    void fileOpsParseDeleteRenameAndSsh() throws Exception {
        String xml = """
                ```xml
                <file path="src/old.py" op="delete"/>
                <file path="src/a.py" op="rename" to="src/b.py"/>
                <file path="src/ok.py">
                print(1)
                </file>
                <ssh alias="prod">
                uptime
                </ssh>
                ```
                """;
        var files = FileOps.parseFiles(xml);
        assertEquals(3, files.size());
        assertTrue(files.get(0).delete());
        assertEquals("src/old.py", files.get(0).path());
        assertTrue(files.get(1).rename());
        assertEquals("src/b.py", files.get(1).toPath());
        assertTrue(files.get(2).write());
        var ssh = FileOps.parseSsh(xml);
        assertEquals(1, ssh.size());
        assertEquals("prod", ssh.getFirst().alias());
        assertTrue(ssh.getFirst().command().contains("uptime"));
        assertFalse(FileOps.looksSafeRel("C:/Windows/x.py"));
        assertFalse(FileOps.looksSafeRel("../secret.py"));
        assertTrue(FileOps.looksSafeRel("alias/src/a.py"));

        Path root = tmp;
        Files.writeString(root.resolve("gone.py"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("from.py"), "old", StandardCharsets.UTF_8);
        var editor = new CodeEditorService();
        var del = editor.parseProposals("""
                ```xml
                <file path="gone.py" op="delete"/>
                ```
                """, root);
        assertEquals(1, del.size());
        editor.apply(del.getFirst(), root);
        assertFalse(Files.exists(root.resolve("gone.py")));
        var ren = editor.parseProposals("""
                ```xml
                <file path="from.py" op="rename" to="to.py"/>
                ```
                """, root);
        editor.apply(ren.getFirst(), root);
        assertFalse(Files.exists(root.resolve("from.py")));
        assertEquals("old", Files.readString(root.resolve("to.py")));
        assertTrue(editor.parseProposals("""
                ```xml
                <file path="C:/Windows/x.py" op="delete"/>
                ```
                """, root).isEmpty());
    }

    @Test
    void safetyVerdictReadsApprovedAndDeny() throws Exception {
        assertTrue(SafetyVerdict.parse("looks fine\nAPPROVED").safe());
        assertFalse(SafetyVerdict.parse("DENY: сеть").safe());
        assertTrue(SafetyVerdict.parse("DENY: сеть").reason().contains("сеть"));
        assertFalse(SafetyVerdict.parse("нет маркера").safe());
        assertTrue(SafetyVerdict.parse("{\"safe\": true, \"reason\": \"ok\"}").safe());
        var exec = com.aipilot.core.SshService.exec(null, "uptime");
        assertFalse(exec.ok());
        ProjectServer srv = new ProjectServer("prod", "", 22, "root");
        srv.password = "secret";
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(srv);
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("\"password\""));
    }

    @Test
    void chatUrlsParseProviderPathsAndRejectTitles() {
        assertEquals("58e84cf5-84c7-4bc7-8358-8b551b65e315",
                ChatUrls.parseQwen("https://chat.qwen.ai/c/58e84cf5-84c7-4bc7-8358-8b551b65e315"));
        assertEquals("38c65ec1-7dd6-4da4-8700-1b10a098b158",
                ChatUrls.parseDeepSeek("https://chat.deepseek.com/a/chat/s/38c65ec1-7dd6-4da4-8700-1b10a098b158"));
        assertTrue(ChatUrls.looksLikeId("58e84cf5-84c7-4bc7-8358-8b551b65e315"));
        assertFalse(ChatUrls.looksLikeId("AIPilot · demo · QW · 20260101"));
        assertEquals("", ChatUrls.normalize("Chat A"));
        assertTrue(ChatTitles.markdown("QW", "id-q", "DS", "id-d").contains("Qwen-id: id-q"));
    }

    @Test
    void answerSanitizerDropsPageChromeButKeepsFileHtmlAndPython() {
        String raw = """
                <nav class="qwen-sidebar">Copy Share</nav>
                done
                ```xml
                <file path="a.html">
                <div class="keep">ok</div>
                </file>
                ```
                ```python
                print(1)
                ```
                """;
        var parsed = AnswerSanitizer.parse(raw);
        assertFalse(parsed.narrative().contains("qwen-sidebar"));
        assertEquals(1, parsed.files().size());
        assertTrue(parsed.files().getFirst().content().contains("<div class=\"keep\">ok</div>"));
        assertEquals(1, parsed.runs().size());
        assertEquals("python", parsed.runs().getFirst().language());
        assertTrue(parsed.reassembled().contains("<file path=\"a.html\">"));
    }

    @Test
    void configPersistsSplitLeftPxAndInvertsLegacyCenter() throws Exception {
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, "{\"splitCenter\":0.70}\n", StandardCharsets.UTF_8);
        AppConfig loaded = AppConfig.load(cfg);
        assertEquals(0.30, loaded.splitCenter, 0.0001);
        assertTrue(loaded.splitLayoutV2);
        assertEquals(260, loaded.splitLeftPx, 0.0001);
        assertEquals(440, loaded.windowWidth, 0.0001);
        assertFalse(loaded.windowMaximized);
        loaded.splitLeftPx = 248;
        loaded.save(cfg);
        AppConfig again = AppConfig.load(cfg);
        assertEquals(248, again.splitLeftPx, 0.0001);
        assertEquals(0.30, again.splitCenter, 0.0001);
    }

    @Test
    void mainLayoutIsCompactAgentWithoutChatHosts() throws Exception {
        String fxml = Files.readString(Path.of("src/main/resources/fxml/main.fxml"), StandardCharsets.UTF_8);
        assertTrue(fxml.contains("fx:id=\"settingsButton\""));
        assertTrue(fxml.contains("fx:id=\"backButton\""));
        assertTrue(fxml.contains("fx:id=\"agentLogHost\""));
        assertTrue(fxml.contains("fx:id=\"apiDot\""));
        assertTrue(fxml.contains("fx:id=\"refreshButton\""));
        assertTrue(fxml.contains("fx:id=\"agentStatusLabel\""));
        assertTrue(fxml.contains("fx:id=\"queueCountLabel\""));
        assertTrue(fxml.contains("fx:id=\"doneCountLabel\""));
        assertTrue(fxml.contains("fx:id=\"detailToggle\""));
        assertTrue(fxml.contains("Не в сети") || fxml.contains("не в сети"));
        assertTrue(fxml.contains("подробно"));
        assertFalse(fxml.contains("chatLogHost"));
        assertFalse(fxml.contains("qwenBrowserHost"));
        assertFalse(fxml.contains("deepseekBrowserHost"));
        assertFalse(fxml.contains("MAXIMIZE"));
        assertFalse(fxml.contains("Выйти"));
        assertFalse(fxml.contains("crumbLabel"));
        assertTrue(fxml.contains("Назад") || fxml.contains("onBackFromSettings"));
        assertFalse(fxml.contains("← Назад"));
    }

    @Test
    void apiBaseUrlSplitsSchemeFromHost() {
        assertEquals("https://", AppConfig.apiBaseSchemePrefix("https://platforma151.chickenkiller.com"));
        assertEquals("platforma151.chickenkiller.com",
                AppConfig.apiBaseHost("https://platforma151.chickenkiller.com/"));
        assertEquals("https://platforma151.chickenkiller.com",
                AppConfig.joinApiBase("https://", "platforma151.chickenkiller.com"));
        assertEquals("http://127.0.0.1:8080", AppConfig.joinApiBase("https://", "http://127.0.0.1:8080"));
        assertFalse(AppConfig.apiBaseHost("https://example.test").startsWith("http"));
    }

    @Test
    void agentStatusIsOnlyOnlineOrOffline() {
        assertEquals(AgentLoop.AgentStatus.ONLINE, AgentLoop.statusOf(AgentLoop.LinkState.OK));
        assertEquals(AgentLoop.AgentStatus.OFFLINE, AgentLoop.statusOf(AgentLoop.LinkState.ERR));
        assertEquals(AgentLoop.AgentStatus.OFFLINE, AgentLoop.statusOf(AgentLoop.LinkState.OFF));
        assertEquals(2, AgentLoop.AgentStatus.values().length);
        for (AgentLoop.AgentStatus status : AgentLoop.AgentStatus.values()) {
            assertTrue(status == AgentLoop.AgentStatus.ONLINE || status == AgentLoop.AgentStatus.OFFLINE);
        }
    }

    @Test
    void autostartShortcutPathUsesTempDirWithoutWritingStartup() throws Exception {
        Path startup = tmp.resolve("Startup");
        Files.createDirectories(startup);
        assertEquals(startup.resolve("AIPilot.lnk"), WindowsAutostart.shortcutPath(startup));
        assertFalse(WindowsAutostart.isEnabled(startup));
        assertTrue(WindowsAutostart.apply(false, startup, null));
        assertFalse(Files.exists(startup.resolve("AIPilot.lnk")));
    }

    @Test
    void deviceKeyGeneratedOnceAndStableOnReload() throws Exception {
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, "{}\n", StandardCharsets.UTF_8);
        AppConfig first = AppConfig.load(cfg);
        assertTrue(AppConfig.looksLikeDeviceKey(first.deviceKey));
        assertEquals(64, first.deviceKey.length());
        AppConfig second = AppConfig.load(cfg);
        assertEquals(first.deviceKey, second.deviceKey);
        assertEquals(first.deviceKey.toLowerCase(), second.deviceKey);
    }

    @Test
    void deviceBindingRequiresMatchingKeyAndAbsoluteFolder() {
        String key = "c".repeat(64);
        Project project = new Project();
        project.id = "p1";
        project.deviceId = key;
        ProjectPath folder = new ProjectPath();
        folder.path = tmp.toAbsolutePath().toString();
        folder.name = "src";
        project.paths.add(folder);
        assertTrue(DeviceBinding.boundHere(project, key));
        assertFalse(DeviceBinding.boundHere(project, "d".repeat(64)));
        project.paths.clear();
        assertFalse(DeviceBinding.boundHere(project, key));
        project.paths.add(new ProjectPath(".", "root"));
        assertFalse(DeviceBinding.boundHere(project, key));
        project.deviceId = "";
        project.paths.clear();
        project.paths.add(folder);
        assertFalse(DeviceBinding.boundHere(project, key));
    }

    @Test
    void projectFromNodeReadsDeviceId() throws Exception {
        String key = "e".repeat(64);
        var node = ApiJson.parse("{\"project_id\":\"p1\",\"project_name\":\"Demo\",\"device_id\":\"" + key
                + "\",\"folders\":[{\"alias\":\"src\",\"path\":\"H:/work/src\"}]}");
        Project project = SiteApiClient.projectFromNode(node);
        assertEquals("p1", project.id);
        assertEquals(key, project.deviceId);
        assertEquals("H:/work/src", project.paths.getFirst().path);
    }

    @Test
    void hashCommandsParseEditDeleteRenameAndRuns() {
        var parsed = HashCommands.parse("""
                #edit_file(src/a.py)#
                print(1)
                #delite_file(src/old.py)#
                #rename_file(src/a.py -> src/b.py)#
                #run_python(print(2))#
                #run_command(dir)#
                """);
        assertEquals(3, parsed.files().size());
        assertEquals(FileOps.WRITE, parsed.files().get(0).op());
        assertEquals("src/a.py", parsed.files().get(0).path());
        assertTrue(parsed.files().get(0).content().contains("print(1)"));
        assertEquals(FileOps.DELETE, parsed.files().get(1).op());
        assertEquals(FileOps.RENAME, parsed.files().get(2).op());
        assertEquals("src/b.py", parsed.files().get(2).toPath());
        assertEquals(2, parsed.runs().size());
        assertEquals("python", parsed.runs().get(0).language());
        assertEquals("cmd", parsed.runs().get(1).language());
        assertTrue(HashCommands.parse("#edit_file(../x.py)#secret").files().isEmpty());
        assertTrue(HashCommands.parse("#edit_file(C:/Windows/x.py)#x").files().isEmpty());
    }

    @Test
    void codeRunRequiresAbsoluteCwdAndRejectsCdUp() {
        assertTrue(CodeRunService.denyReason("cd ..").contains("опасн"));
        var result = CodeRunService.run(new CodeRunService.RunBlock("cmd", "echo hi"), null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("папк"));
        var blocked = CodeRunService.run(new CodeRunService.RunBlock("cmd", "cd .."), tmp.toAbsolutePath());
        assertFalse(blocked.ok());
    }
}
