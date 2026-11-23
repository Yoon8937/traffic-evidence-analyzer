package com.backend.video;

import com.backend.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.File;

@Service
public class S3StorageService {

    private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;
    private final String bucket;

    public S3StorageService(
            S3Client s3Client,
            @Value("${app.aws.s3.bucket}") String bucket
    ) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    public void upload(String s3Key, File file, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .contentType(contentType)
                    .build();

//            log.info("S3 upload start...");
            s3Client.putObject(request, RequestBody.fromFile(file));
//            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromFile(file));
//            log.info("S3 upload end!");
//            log.info("etag= ", response.eTag());
        } catch (SdkException e) {
            throw new StorageException("S3 upload failed. s3Key=" + s3Key, e);
        }
    }

    public void delete(String s3Key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build();
            s3Client.deleteObject(request);
        } catch (SdkException e) {
            log.error("S3 delete failed. s3Key={}", s3Key, e);
            throw e;
        }
    }
}
