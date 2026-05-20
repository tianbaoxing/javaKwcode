package com.kwcode.server;

import java.util.*;

/**
 * Server API数据模型
 * 对应Python: kaiwu/server/models.py
 *
 * @origin kaiwu/server/models.py
 */
public class Models {

    /** 版本号 */
    public static final String VERSION = "1.0.0-SNAPSHOT";

    /**
     * 任务提交请求
     * @origin kaiwu/server/models.py::TaskRequest
     */
    public static class TaskRequest {
        private String input;
        private String projectRoot = ".";
        private boolean noSearch = false;
        private List<String> imagePaths = new ArrayList<>();

        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        public String getProjectRoot() { return projectRoot; }
        public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
        public boolean isNoSearch() { return noSearch; }
        public void setNoSearch(boolean noSearch) { this.noSearch = noSearch; }
        public List<String> getImagePaths() { return imagePaths; }
        public void setImagePaths(List<String> imagePaths) { this.imagePaths = imagePaths; }
    }

    /**
     * 任务提交响应
     * @origin kaiwu/server/models.py::TaskResponse
     */
    public static class TaskResponse {
        private String taskId;
        private String status = "accepted";

        public TaskResponse(String taskId) { this.taskId = taskId; }
        public String getTaskId() { return taskId; }
        public String getStatus() { return status; }
    }

    /**
     * 健康检查响应
     * @origin kaiwu/server/models.py::HealthResponse
     */
    public static class HealthResponse {
        private String status = "ok";
        private String version = VERSION;
        private String model = "";
        private String projectRoot = "";

        public String getStatus() { return status; }
        public String getVersion() { return version; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getProjectRoot() { return projectRoot; }
        public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
    }

    /**
     * 服务状态响应
     * @origin kaiwu/server/models.py::StatusResponse
     */
    public static class StatusResponse {
        private String model = "";
        private String projectRoot = "";
        private int expertsLoaded = 0;
        private boolean searchEnabled = false;
        private double uptimeSeconds = 0.0;

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getProjectRoot() { return projectRoot; }
        public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
        public int getExpertsLoaded() { return expertsLoaded; }
        public void setExpertsLoaded(int expertsLoaded) { this.expertsLoaded = expertsLoaded; }
        public boolean isSearchEnabled() { return searchEnabled; }
        public void setSearchEnabled(boolean searchEnabled) { this.searchEnabled = searchEnabled; }
        public double getUptimeSeconds() { return uptimeSeconds; }
        public void setUptimeSeconds(double uptimeSeconds) { this.uptimeSeconds = uptimeSeconds; }
    }

    /**
     * 文件内容响应
     * @origin kaiwu/server/models.py::FileContent
     */
    public static class FileContent {
        private String path;
        private String content;
        private String language = "";
        private int lines = 0;

        public FileContent(String path, String content) {
            this.path = path;
            this.content = content;
            this.lines = content.split("\n", -1).length;
        }

        public String getPath() { return path; }
        public String getContent() { return content; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public int getLines() { return lines; }
    }

    /**
     * 清单响应
     * @origin kaiwu/server/models.py::ManifestResponse
     */
    public static class ManifestResponse {
        private Map<String, Map<String, String>> signatures = new LinkedHashMap<>();
        private Map<String, Map<String, String>> constants = new LinkedHashMap<>();
        private int fileCount = 0;

        public Map<String, Map<String, String>> getSignatures() { return signatures; }
        public void setSignatures(Map<String, Map<String, String>> signatures) { this.signatures = signatures; }
        public Map<String, Map<String, String>> getConstants() { return constants; }
        public void setConstants(Map<String, Map<String, String>> constants) { this.constants = constants; }
        public int getFileCount() { return fileCount; }
        public void setFileCount(int fileCount) { this.fileCount = fileCount; }
    }
}
