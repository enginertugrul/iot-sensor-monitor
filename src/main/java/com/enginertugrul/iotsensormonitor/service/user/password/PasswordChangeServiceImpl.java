package com.enginertugrul.iotsensormonitor.service.user.password;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;





@Service
public class PasswordChangeServiceImpl implements PasswordChangeService {

    private static final int MINIMUM_PASSWORD_LENGTH = 8;
    private static final int MAXIMUM_PASSWORD_LENGTH = 72;

    private final AppUserRepository appUserRepository;
    private final PasswordResetChallengeRepository passwordResetChallengeRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;




    public PasswordChangeServiceImpl(AppUserRepository appUserRepository,PasswordResetChallengeRepository passwordResetChallengeRepository,PasswordEncoder passwordEncoder,ApplicationEventPublisher eventPublisher) {
        this.appUserRepository = appUserRepository;
        this.passwordResetChallengeRepository = passwordResetChallengeRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }





    @Override
    @Transactional
    public PasswordChangeResult changePassword(Long userId,String currentPassword,String newPassword) {
        Long requiredUserId = requireUserId(userId);

        if (!isValidPassword(currentPassword)) {
            return PasswordChangeResult.CURRENT_PASSWORD_INVALID;
        }

        if (!isValidPassword(newPassword)) {
            return PasswordChangeResult.NEW_PASSWORD_INVALID;
        }

        AppUser user = appUserRepository.findByIdForUpdate(requiredUserId).orElseThrow(()-> new NoSuchElementException("User not found"));

        if (!passwordEncoder.matches(currentPassword,user.getPasswordHash())) {
            return PasswordChangeResult.CURRENT_PASSWORD_INVALID;
        }

        if (passwordEncoder.matches(newPassword,user.getPasswordHash())) {
            return PasswordChangeResult.NEW_PASSWORD_UNCHANGED;
        }

        passwordResetChallengeRepository.findByUserIdForUpdate(requiredUserId).ifPresent(passwordResetChallengeRepository::delete);
        user.updatePasswordHash(passwordEncoder.encode(newPassword));
        eventPublisher.publishEvent(new PasswordChangedEvent(requiredUserId));

        return PasswordChangeResult.PASSWORD_CHANGED;
    }





    private Long requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }

        return userId;
    }




    private boolean isValidPassword(String password) {
        return password != null
                && !password.isBlank()
                && password.length() >= MINIMUM_PASSWORD_LENGTH
                && password.length() <= MAXIMUM_PASSWORD_LENGTH;
    }

}