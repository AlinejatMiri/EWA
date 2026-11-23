package edu.ku.commerce.omnichannel_commerce.common.errors;

import java.time.Instant;

public record ApiError(int status, String error, String message, String path, Instant timestamp) {}
