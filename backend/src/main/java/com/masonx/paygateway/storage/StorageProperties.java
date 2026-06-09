package com.masonx.paygateway.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String type = "local";
    private Local local = new Local();
    private S3 s3 = new S3();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Local getLocal() { return local; }
    public void setLocal(Local local) { this.local = local; }
    public S3 getS3() { return s3; }
    public void setS3(S3 s3) { this.s3 = s3; }

    public static class Local {
        private String baseDir = "./uploads";
        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    }

    public static class S3 {
        private String bucket;
        private String region = "us-east-1";
        private String prefix = "";
        private long presignedUrlExpirySeconds = 3600;

        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public long getPresignedUrlExpirySeconds() { return presignedUrlExpirySeconds; }
        public void setPresignedUrlExpirySeconds(long v) { this.presignedUrlExpirySeconds = v; }
    }
}
