package org.apereo.cas.adaptors.yubikey;

import com.yubico.client.v2.ResponseStatus;
import com.yubico.client.v2.VerificationResponse;
import com.yubico.client.v2.YubicoClient;
import com.yubico.client.v2.exceptions.YubicoValidationFailure;
import com.yubico.client.v2.exceptions.YubicoVerificationException;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.HandlerResult;
import org.apereo.cas.authentication.PreventedException;
import org.apereo.cas.authentication.handler.support.AbstractPreAndPostProcessingAuthenticationHandler;
import org.apereo.cas.web.support.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.webflow.execution.RequestContext;
import org.springframework.webflow.execution.RequestContextHolder;

import javax.security.auth.login.AccountNotFoundException;
import javax.security.auth.login.FailedLoginException;
import java.security.GeneralSecurityException;

/**
 * An authentication handler that uses the Yubico cloud validation
 * platform to authenticate one-time password tokens that are
 * issued by a YubiKey device. To use YubiCloud you need a
 * client id and an API key which must be obtained from Yubico.
 * <p>For more info, please visit
 * <a href="http://yubico.github.io/yubico-java-client/">this link</a></p>
 *
 * @author Misagh Moayyed
 * @since 4.1
 */
public class YubiKeyAuthenticationHandler extends AbstractPreAndPostProcessingAuthenticationHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(YubiKeyAuthenticationHandler.class);
    
    private final YubiKeyAccountRegistry registry;
    private final YubicoClient client;

    /**
     * Prepares the Yubico client with the received clientId and secretKey. If you wish to
     * limit the usage of this handler only to a particular set of yubikey accounts for a special
     * group of users, you may verify an compliant implementation of {@link YubiKeyAccountRegistry}.
     * By default, all accounts are allowed.
     *
     * @param clientId  the client id
     * @param secretKey the secret key
     * @param registry  the account registry which holds registrations.
     */
    public YubiKeyAuthenticationHandler(final Integer clientId, final String secretKey, final YubiKeyAccountRegistry registry) {
        this.registry = registry;
        this.client = YubicoClient.getClient(clientId, secretKey);

        if (this.registry == null) {
            LOGGER.warn("No YubiKey account registry is defined. All credentials are considered "
                            + "eligible for YubiKey authentication. Consider providing an account registry implementation via [{}]",
                    YubiKeyAccountRegistry.class.getName());
        }
    }

    public YubiKeyAuthenticationHandler(final Integer clientId, final String secretKey) {
        this(clientId, secretKey, null);
    }   
    
    @Override
    protected HandlerResult doAuthentication(final Credential credential) throws GeneralSecurityException, PreventedException {
        final YubiKeyCredential yubiKeyCredential = (YubiKeyCredential) credential;

        final String otp = yubiKeyCredential.getToken();

        if (!YubicoClient.isValidOTPFormat(otp)) {
            LOGGER.debug("Invalid OTP format [{}]", otp);
            throw new AccountNotFoundException("OTP format is invalid");
        }

        final RequestContext context = RequestContextHolder.getRequestContext();
        final String uid = WebUtils.getAuthentication(context).getPrincipal().getId();
        final String publicId = YubicoClient.getPublicId(otp);
        if (this.registry != null
                && !this.registry.isYubiKeyRegisteredFor(uid, publicId)) {
            LOGGER.debug("YubiKey public id [{}] is not registered for user [{}]", publicId, uid);
            throw new AccountNotFoundException("YubiKey id is not recognized in registry");
        }

        try {
            final VerificationResponse response = this.client.verify(otp);
            final ResponseStatus status = response.getStatus();
            if (status.compareTo(ResponseStatus.OK) == 0) {
                LOGGER.debug("YubiKey response status [{}] at [{}]", status, response.getTimestamp());
                return createHandlerResult(yubiKeyCredential, this.principalFactory.createPrincipal(uid), null);
            }
            throw new FailedLoginException("Authentication failed with status: " + status);
        } catch (final YubicoVerificationException | YubicoValidationFailure e) {
            LOGGER.error(e.getMessage(), e);
            throw new FailedLoginException("YubiKey validation failed: " + e.getMessage());
        }
    }

    public YubiKeyAccountRegistry getRegistry() {
        return this.registry;
    }

    public YubicoClient getClient() {
        return this.client;
    }

    @Override
    public boolean supports(final Credential credential) {
        return YubiKeyCredential.class.isAssignableFrom(credential.getClass());
    }
}
