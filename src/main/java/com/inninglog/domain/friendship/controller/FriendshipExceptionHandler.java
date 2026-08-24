package com.inninglog.domain.friendship.controller;

import com.inninglog.domain.friendship.exception.AlreadyFriendsException;
import com.inninglog.domain.friendship.exception.FriendRequestAlreadySentException;
import com.inninglog.domain.friendship.exception.FriendshipActionNotAllowedException;
import com.inninglog.domain.friendship.exception.FriendshipNotFoundException;
import com.inninglog.domain.friendship.exception.FriendshipProfileRequiredException;
import com.inninglog.domain.friendship.exception.FriendshipUserNotFoundException;
import com.inninglog.domain.friendship.exception.IncomingFriendRequestExistsException;
import com.inninglog.domain.friendship.exception.InvalidFriendshipStateException;
import com.inninglog.domain.friendship.exception.SelfFriendRequestException;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {FriendshipController.class, UserSearchController.class})
public class FriendshipExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<FriendshipErrorResponse> handleCurrentUserNotFound(UserNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(FriendshipUserNotFoundException.class)
    public ResponseEntity<FriendshipErrorResponse> handleCandidateNotFound(FriendshipUserNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "FRIEND_USER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(FriendshipNotFoundException.class)
    public ResponseEntity<FriendshipErrorResponse> handleFriendshipNotFound(FriendshipNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "FRIENDSHIP_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AccountDeletedException.class)
    public ResponseEntity<FriendshipErrorResponse> handleDeletedAccount(AccountDeletedException exception) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_DELETED", exception.getMessage());
    }

    @ExceptionHandler(FriendshipProfileRequiredException.class)
    public ResponseEntity<FriendshipErrorResponse> handleProfileRequired(FriendshipProfileRequiredException exception) {
        return error(HttpStatus.CONFLICT, "PROFILE_SETUP_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler(SelfFriendRequestException.class)
    public ResponseEntity<FriendshipErrorResponse> handleSelfRequest(SelfFriendRequestException exception) {
        return error(HttpStatus.CONFLICT, "SELF_FRIEND_REQUEST_NOT_ALLOWED", exception.getMessage());
    }

    @ExceptionHandler(AlreadyFriendsException.class)
    public ResponseEntity<FriendshipErrorResponse> handleAlreadyFriends(AlreadyFriendsException exception) {
        return error(HttpStatus.CONFLICT, "ALREADY_FRIENDS", exception.getMessage());
    }

    @ExceptionHandler(FriendRequestAlreadySentException.class)
    public ResponseEntity<FriendshipErrorResponse> handleAlreadySent(FriendRequestAlreadySentException exception) {
        return error(HttpStatus.CONFLICT, "FRIEND_REQUEST_ALREADY_SENT", exception.getMessage());
    }

    @ExceptionHandler(IncomingFriendRequestExistsException.class)
    public ResponseEntity<FriendshipErrorResponse> handleIncomingRequest(
            IncomingFriendRequestExistsException exception
    ) {
        return error(HttpStatus.CONFLICT, "INCOMING_FRIEND_REQUEST_EXISTS", exception.getMessage());
    }

    @ExceptionHandler(InvalidFriendshipStateException.class)
    public ResponseEntity<FriendshipErrorResponse> handleInvalidState(InvalidFriendshipStateException exception) {
        return error(HttpStatus.CONFLICT, "INVALID_FRIENDSHIP_STATE", exception.getMessage());
    }

    @ExceptionHandler(FriendshipActionNotAllowedException.class)
    public ResponseEntity<FriendshipErrorResponse> handleActionNotAllowed(
            FriendshipActionNotAllowedException exception
    ) {
        return error(HttpStatus.FORBIDDEN, "FRIENDSHIP_ACTION_NOT_ALLOWED", exception.getMessage());
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<FriendshipErrorResponse> handleInvalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The friendship request is invalid.");
    }

    private static ResponseEntity<FriendshipErrorResponse> error(
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status)
                .body(new FriendshipErrorResponse(code, message, Instant.now()));
    }

    @Schema(description = "친구 API 오류 응답")
    public record FriendshipErrorResponse(
            @Schema(example = "FRIENDSHIP_NOT_FOUND") String code,
            String message,
            Instant timestamp
    ) {
    }
}
