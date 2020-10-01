package org.sagebionetworks.upload.multipart;

import java.net.URL;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.sagebionetworks.aws.SynapseS3Client;
import org.sagebionetworks.repo.model.dbo.file.CompositeMultipartUploadStatus;
import org.sagebionetworks.repo.model.file.AddPartRequest;
import org.sagebionetworks.repo.model.file.CompleteMultipartRequest;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.file.MultipartUploadCopyRequest;
import org.sagebionetworks.repo.model.file.MultipartUploadRequest;
import org.sagebionetworks.repo.model.file.PartMD5;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.util.ContentDispositionUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.CopyPartRequest;
import com.amazonaws.services.s3.model.CopyPartResult;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.util.BinaryUtils;

/**
 * This class handles the interaction with S3 during the steps of a multi-part upload
 */
public class S3MultipartUploadDAOImpl implements CloudServiceMultipartUploadDAO {

	private static final String S3_HEADER_COPY_RANGE = "x-amz-copy-source-range";

	private static final String S3_HEADER_COPY_SOURCE = "x-amz-copy-source";

	private static final String S3_PARAM_UPLOAD_ID = "uploadId";

	private static final String S3_PARAM_PART_NUMBER = "partNumber";

	// 15 minute.
	private static final int PRE_SIGNED_URL_EXPIRATION_MS = 1000 * 60 * 15;

	@Autowired
	private SynapseS3Client s3Client;

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sagebionetworks.upload.multipart.S3MultipartUploadDAO#
	 * initiateMultipartUpload(java.lang.String, java.lang.String,
	 * org.sagebionetworks.repo.model.file.MultipartUploadRequest)
	 */
	@Override
	public String initiateMultipartUpload(String bucket, String key,
			MultipartUploadRequest request) {
		final String contentType = getContentType(request.getContentType());
		
		return initiateMultipartUpload(bucket, key, contentType, request.getFileName(), request.getContentMD5Hex());
	}
	
	@Override
	public String initiateMultipartUploadCopy(String bucket, String key, MultipartUploadCopyRequest request, FileHandle fileHandle) {
		final String contentType = getContentType(fileHandle.getContentType());
		
		if (!(fileHandle instanceof S3FileHandle)) {
			throw new UnsupportedOperationException("The file handle must point to an S3 location.");
		}

		validateSameRegionCopy(((S3FileHandle) fileHandle).getBucketName(), bucket);
		
		return initiateMultipartUpload(bucket, key, contentType, request.getFileName(), fileHandle.getContentMd5());
	}
	
	private String initiateMultipartUpload(String bucket, String key, String contentType, String fileName, String contentMD5) {
		ObjectMetadata objectMetadata = new ObjectMetadata();
		
		objectMetadata.setContentType(contentType);
		objectMetadata.setContentDisposition(ContentDispositionUtils.getContentDispositionValue(fileName));
		objectMetadata.setContentMD5(BinaryUtils.toBase64(BinaryUtils.fromHex(contentMD5)));
		
		InitiateMultipartUploadResult result = s3Client
				.initiateMultipartUpload(new InitiateMultipartUploadRequest(bucket, key, objectMetadata)
				.withCannedACL(CannedAccessControlList.BucketOwnerFullControl));
		
		return result.getUploadId();
	}
	
	private void validateSameRegionCopy(String sourceBucket, String destinationBucket) {
		Region sourceRegion = s3Client.getRegionForBucket(sourceBucket);
		Region targetRegion = s3Client.getRegionForBucket(destinationBucket);
		
		if (!sourceRegion.equals(targetRegion)) {
			throw new UnsupportedOperationException("Copying a file that is stored in a different region than the destination is not supported.");
		}
	}
	
	private static String getContentType(String contentType) {
		if (StringUtils.isEmpty(contentType)) {
			return "application/octet-stream";
		}
		return contentType;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sagebionetworks.upload.multipart.S3MultipartUploadDAO#
	 * createPreSignedPutUrl(java.lang.String, java.lang.String)
	 */
	@Override
	public PresignedUrl createPartUploadPreSignedUrl(String bucket, String partKey, String contentType) {
		long expiration = System.currentTimeMillis()+ PRE_SIGNED_URL_EXPIRATION_MS;
		
		GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
				bucket, partKey).withMethod(HttpMethod.PUT).withExpiration(
				new Date(expiration));
		
		PresignedUrl presignedUrl = new PresignedUrl();
		
		if (StringUtils.isNotEmpty(contentType)){
			request.setContentType(contentType);
			presignedUrl.withSignedHeader(HttpHeaders.CONTENT_TYPE, contentType);
		}
		
		URL url = s3Client.generatePresignedUrl(request);

		presignedUrl.withUrl(url);
		
		return presignedUrl;
	}
	
	@Override
	public PresignedUrl createPartUploadCopyPresignedUrl(CompositeMultipartUploadStatus status, long partNumber,
			String contentType) {
		if (status.getSourceFileHandleId() == null) {
			throw new IllegalStateException("Expected a source file, found none.");
		}
		
		if (status.getFileSize() == null) {
			throw new IllegalStateException("Expected the source file size, found none.");
		}
		
		if (status.getPartSize() == null) {
			throw new IllegalStateException("Expected a part size, found none.");
		}
		
		if (status.getSourceBucket() == null) {
			throw new IllegalStateException("Expected the source file bucket, found none.");
		}
		
		if (status.getSourceKey() == null) {
			throw new IllegalStateException("Expected the source file bucket key, found none.");
		}
		
		// Computes the byte range
		long bytePosition = (partNumber - 1) * status.getPartSize();
		
		// The last part might be smaller than partSize, so check to make sure that lastByte isn't beyond the end of the object.
		long lastByte = Math.min(bytePosition + status.getPartSize() - 1, status.getFileSize() - 1);
		
		long expiration = System.currentTimeMillis()+ PRE_SIGNED_URL_EXPIRATION_MS;
		
		GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(status.getBucket(), status.getKey())
				.withMethod(HttpMethod.PUT)
				.withExpiration(new Date(expiration));
		
		request.addRequestParameter(S3_PARAM_PART_NUMBER, String.valueOf(partNumber));
		request.addRequestParameter(S3_PARAM_UPLOAD_ID, status.getUploadToken());
		
		request.putCustomRequestHeader(S3_HEADER_COPY_SOURCE, status.getSourceBucket() + "/" + status.getSourceKey());
		request.putCustomRequestHeader(S3_HEADER_COPY_RANGE, String.format("bytes=%s-%s", bytePosition, lastByte));
		
		PresignedUrl presignedUrl = new PresignedUrl();
		
		if (StringUtils.isNotEmpty(contentType)){
			request.setContentType(contentType);
			presignedUrl.withSignedHeader(HttpHeaders.CONTENT_TYPE, contentType);
		}
		
		request.getCustomRequestHeaders().forEach((headerKey, headerValue) -> {
			presignedUrl.withSignedHeader(headerKey, headerValue);
		});
		
		URL url = s3Client.generatePresignedUrl(request);

		presignedUrl.withUrl(url);
		
		return presignedUrl;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.sagebionetworks.upload.multipart.S3MultipartUploadDAO#addPart(org
	 * .sagebionetworks.upload.multipart.AddPartRequest)
	 */
	@Override
	public void validateAndAddPart(AddPartRequest request) {
		CopyPartRequest cpr = new CopyPartRequest();
		cpr.setSourceBucketName(request.getBucket());
		cpr.setSourceKey(request.getPartKey());
		cpr.setDestinationKey(request.getKey());
		cpr.setDestinationBucketName(request.getBucket());
		cpr.setUploadId(request.getUploadToken());
		cpr.setPartNumber((int) request.getPartNumber());
		// only add if the etag matches.
		cpr.withMatchingETagConstraint(request.getPartMD5Hex());
		CopyPartResult result = s3Client.copyPart(cpr);
		if (result == null) {
			throw new IllegalArgumentException(
					"The provided MD5 does not match the MD5 of the uploaded part.  Please re-upload the part.");
		}
		// After copying the part we can delete the old part file.
		s3Client.deleteObject(request.getBucket(), request.getPartKey());
	}

	@Override
	public void validatePartCopy(CompositeMultipartUploadStatus status, long partNumber, String partMD5Hex) {
		// Nothing to validate
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.sagebionetworks.upload.multipart.S3MultipartUploadDAO#
	 * completeMultipartUpload
	 * (org.sagebionetworks.repo.model.file.CompleteMultipartRequest)
	 */
	@Override
	public long completeMultipartUpload(CompleteMultipartRequest request) {
		CompleteMultipartUploadRequest cmur = new CompleteMultipartUploadRequest();
		
		cmur.setBucketName(request.getBucket());
		cmur.setKey(request.getKey());
		cmur.setUploadId(request.getUploadToken());
		// convert the parts MD5s to etags
		List<PartETag> partEtags = new LinkedList<PartETag>();
		
		cmur.setPartETags(partEtags);
		
		for (PartMD5 partMD5 : request.getAddedParts()) {
			String partEtag = partMD5.getPartMD5Hex();
			partEtags.add(new PartETag(partMD5.getPartNumber(), partEtag));
		}

		try {
			s3Client.completeMultipartUpload(cmur);
		} catch (AmazonS3Exception e) {
			// thrown when given a bad request.
			throw new IllegalArgumentException(e.getMessage(), e);
		}
		
		// Lookup the final size of this file
		ObjectMetadata resultFileMetadata = s3Client.getObjectMetadata(request.getBucket(), request.getKey());
		
		return resultFileMetadata.getContentLength();
	}

}
