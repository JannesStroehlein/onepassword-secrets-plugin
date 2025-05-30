package com.onepassword.jenkins.plugins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsUnavailableException;
import com.cloudbees.plugins.credentials.matchers.IdMatcher;
import com.onepassword.jenkins.plugins.config.OnePasswordConfig;
import com.onepassword.jenkins.plugins.config.OnePasswordConfigResolver;
import com.onepassword.jenkins.plugins.exception.OnePasswordException;
import com.onepassword.jenkins.plugins.model.OnePasswordSecret;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class OnePasswordAccessor implements Serializable {

    private static final long serialVersionUID = 1456115587313159751L;
    private OnePasswordConfig config;

    private static final String GENERATED_CONNECT_TOKEN_ID = "onepassword_connect_token_autogenerated";
    private static final String GENERATED_SERVICE_ACCOUNT_TOKEN_ID = "onepassword_service_account_token_autogenerated";
    private static final String GENERATED_CONNECT_DESCRIPTION = "Auto-generated credential of the 1Password Connect Token from environment";
    private static final String GENERATED_SERVICE_ACCOUNT_DESCRIPTION = "Auto-generated credential of the 1Password Service Account Token from environment";

    // Environment variables
    private static final String envOPConnectHost = "OP_CONNECT_HOST";
    private static final String envOPConnectToken = "OP_CONNECT_TOKEN";
    private static final String envOPServiceAccountToken = "OP_SERVICE_ACCOUNT_TOKEN";
    private static final String envOPCLIPath = "OP_CLI_PATH";
    private static final String envOPIntegrationName = "OP_INTEGRATION_NAME";
    private static final String envOPIntegrationID = "OP_INTEGRATION_ID";
    private static final String envOPIntegrationBuildNumber = "OP_INTEGRATION_BUILDNUMBER";

    // User-Agent values
    private static final String OPIntegrationName = "1Password Jenkins Plugin";
    private static final String OPIntegrationID = "JEN";
    private static final String OPIntegrationBuildNumber = "0001001";

    public OnePasswordAccessor() {
        this.config = new OnePasswordConfig();
    }

    public OnePasswordAccessor(OnePasswordConfig config) {
        this.config = config;
    }

    public OnePasswordAccessor init() {
        return this;
    }

    public OnePasswordConfig getConfig() {
        return config;
    }

    public void setConfig(OnePasswordConfig config) {
        this.config = config;
    }

    public static Map<String, String> retrieveSecrets(Run<?, ?> run, PrintStream logger, EnvVars envVars, OnePasswordConfig initialConfig, List<OnePasswordSecret> secrets) {
        Map<String, String> overrides = new HashMap<>();

        OnePasswordConfig config = pullAndMergeConfig(run, initialConfig, envVars);
        String connectHost = config.getConnectHost();

        StringCredentials connectCredential = config.getConnectCredential();
        if (connectCredential == null) {
            connectCredential = retrieveCredentials(run, config::getConnectCredentialId);
        }

        StringCredentials serviceAccountCredential = config.getServiceAccountCredential();
        if (serviceAccountCredential == null) {
            serviceAccountCredential = retrieveCredentials(run, config::getServiceAccountCredentialId);
        }

        if (serviceAccountCredential == null) {
            if (connectCredential != null && StringUtils.isBlank(connectHost)) {
                throw new OnePasswordException("The Connect host is not configured - please provide the host to the Connect instance.");
            } else if (!StringUtils.isBlank(connectHost) && connectCredential == null) {
                throw new OnePasswordException("The Connect credential is not configured - please provide the credential of the Connect instance.");
            } else if (connectCredential == null && StringUtils.isBlank(connectHost)){
                throw new OnePasswordException("No credential has been configured - please provide either the credential and host of the Connect instance or the credential of the Service Account Token.");
            }
        }

        String opCLIPath = config.getOpCLIPath();
        if (StringUtils.isBlank(opCLIPath)) {
            opCLIPath = envVars.get("WORKSPACE");
        }

        ProcessBuilder pb = new ProcessBuilder();
        Map<String, String> env = pb.environment();

        if (!StringUtils.isBlank(connectHost)) {
            env.putIfAbsent(envOPConnectHost, connectHost);
        }
        if (connectCredential != null) {
            env.putIfAbsent(envOPConnectToken, connectCredential.getSecret().getPlainText());
        }
        if (serviceAccountCredential != null) {
            env.putIfAbsent(envOPServiceAccountToken, serviceAccountCredential.getSecret().getPlainText());
        }

        // Passing User-Agent information to CLI
        env.put(envOPIntegrationName, OPIntegrationName);
        env.put(envOPIntegrationID, OPIntegrationID);
        env.put(envOPIntegrationBuildNumber, OPIntegrationBuildNumber);

        pb.directory(new File(opCLIPath));

        for (OnePasswordSecret secret : secrets) {

            logger.printf("Retrieving secret %s%n", secret.getEnvVar());
            String opCliExecutablePath = opCLIPath.toLowerCase().endsWith(".exe")
                ? opCLIPath
                : opCLIPath + "/op";
            String[] commands = {opCliExecutablePath, "read", secret.getSecretRef()};
            try {
                Process pr = pb.command(commands).start();
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8));
                BufferedReader stdError = new BufferedReader(new InputStreamReader(pr.getErrorStream(), StandardCharsets.UTF_8));
                String secretValue = stdInput.lines().collect(Collectors.joining(System.lineSeparator()));
                if (StringUtils.isBlank(secretValue)) {
                    String s;
                    StringBuilder errorMessage = new StringBuilder();
                    while ((s = stdError.readLine()) != null) {
                        errorMessage.append(s).append("\n");
                    }
                    if (StringUtils.isBlank(errorMessage.toString())) {
                        throw new OnePasswordException("Secret with reference " + secret.getSecretRef() + "is empty.");
                    }
                    throw new OnePasswordException("Error retrieving secret " + secret.getSecretRef() + ":\n" + errorMessage + "\n");
                }

                overrides.put(secret.getEnvVar(), secretValue);
                stdInput.close();
                stdError.close();
            } catch (IOException e) {
                throw new OnePasswordException("Error running command " + Arrays.toString(commands) + ":\n" + e.getMessage() + "\n");
            }
        }

        if (env.get(envOPConnectHost) != null) {
            env.remove(envOPConnectHost);
        }
        if (env.get(envOPConnectToken) != null) {
            env.remove(envOPConnectToken);
        }
        if (env.get(envOPServiceAccountToken) != null) {
            env.remove(envOPServiceAccountToken);
        }

        return overrides;
    }

    public static Map<String, String> loadSecrets(Run<?, ?> run, PrintStream logger, EnvVars envVars, OnePasswordConfig initialConfig, List<OnePasswordSecret> secrets) {
        if (secrets == null) {
            secrets = new ArrayList<>();
        }
        for (Map.Entry<String, String> envVar : envVars.entrySet()) {
            if (isOPReference(envVar.getValue())) {
                secrets.add(new OnePasswordSecret(envVar.getKey(), envVar.getValue()));
            }
        }

        return retrieveSecrets(run, logger, envVars, initialConfig, secrets);
    }

    public static StringCredentials retrieveCredentials(Run build, Supplier<String> function) {
        if (Jenkins.getInstanceOrNull() != null) {
            String id = function.get();
            if (StringUtils.isBlank(id)) {
                return null;
            }
            List<StringCredentials> credentials = CredentialsProvider
                    .lookupCredentials(StringCredentials.class, build.getParent(), ACL.SYSTEM,
                            Collections.emptyList());
            StringCredentials tokenCredential = CredentialsMatchers
                    .firstOrNull(credentials, new IdMatcher(id));

            if (tokenCredential == null) {
                throw new CredentialsUnavailableException(id);
            }

            return tokenCredential;
        }

        return null;
    }

    public static OnePasswordConfig pullAndMergeConfig(Run<?, ?> build, OnePasswordConfig buildConfig, EnvVars envVars) {
        OnePasswordConfig config = resolveConfigFromEnv(build, envVars);
        if (config == null) {
            config = buildConfig;
        } else if (buildConfig != null) {
            if (buildConfig.hasConnectHost() && !StringUtils.isBlank(buildConfig.getConnectHost())) {
                config.setConnectHost(buildConfig.getConnectHost());
            }
            if (buildConfig.hasConnectCredentialId() && !StringUtils.isBlank(buildConfig.getConnectCredentialId())) {
                config.setConnectCredentialId(buildConfig.getConnectCredentialId());
                config.setConnectCredential(null);
            }
            if (buildConfig.hasServiceAccountCredentialId() && !StringUtils.isBlank(buildConfig.getServiceAccountCredentialId())) {
                config.setServiceAccountCredentialId(buildConfig.getServiceAccountCredentialId());
                config.setServiceAccountCredential(null);
            }
        }

        for (OnePasswordConfigResolver resolver : ExtensionList.lookup(OnePasswordConfigResolver.class)) {
            if (config != null) {
                config = config.mergeWithParent(resolver.forJob(build.getParent()));
            } else {
                config = resolver.forJob(build.getParent());
            }
        }

        if (config == null) {
            throw new OnePasswordException("No config found - please configure 1Password");
        }

        return config;
    }

    public static OnePasswordConfig resolveConfigFromEnv(Run<?, ?> build, EnvVars envVars) {
        Optional<String> connectHost = getPropertyByEnvOrSystemProperty(envOPConnectHost, "jenkins.onepassword.connect_host", envVars);
        Optional<String> connectToken = getPropertyByEnvOrSystemProperty(envOPConnectToken, "jenkins.onepassword.connect_token", envVars);
        Optional<String> serviceAccountToken = getPropertyByEnvOrSystemProperty(envOPServiceAccountToken, "jenkins.onepassword.service_account_token", envVars);
        Optional<String> opCLIPath = getPropertyByEnvOrSystemProperty(envOPCLIPath, "jenkins.onepassword.op_cli_path", envVars);

        if (!connectHost.isPresent() && !connectToken.isPresent() && !serviceAccountToken.isPresent() && !opCLIPath.isPresent()) {
            return null;
        }

        OnePasswordConfig config = new OnePasswordConfig();
        connectHost.ifPresent(config::setConnectHost);
        opCLIPath.ifPresent(config::setOpCLIPath);
        if (connectToken.isPresent()) {
            List<StringCredentials> credentials = CredentialsProvider
                    .lookupCredentials(StringCredentials.class, build.getParent(), ACL.SYSTEM,
                            Collections.emptyList());

            for (StringCredentials cred : credentials) {
                if (cred.getSecret().getPlainText().equals(connectToken.get()) || cred.getId().equals(connectToken.get())) {
                    config.setConnectCredential(cred);
                    config.setConnectCredentialId(cred.getId());
                }
            }

            if (StringUtils.isBlank(config.getConnectCredentialId())) {
                StringCredentialsImpl generatedCredentials = new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        GENERATED_CONNECT_TOKEN_ID,
                        GENERATED_CONNECT_DESCRIPTION,
                        Secret.fromString(connectToken.get()));
                config.setConnectCredential(generatedCredentials);
                config.setConnectCredentialId(generatedCredentials.getId());
            }
        }
        if (serviceAccountToken.isPresent()) {
            List<StringCredentials> credentials = CredentialsProvider
                    .lookupCredentials(StringCredentials.class, build.getParent(), ACL.SYSTEM,
                            Collections.emptyList());

            for (StringCredentials cred : credentials) {
                if (cred.getSecret().getPlainText().equals(serviceAccountToken.get()) || cred.getId().equals(serviceAccountToken.get())) {
                    config.setServiceAccountCredential(cred);
                    config.setServiceAccountCredentialId(cred.getId());
                }
            }

            if (StringUtils.isBlank(config.getServiceAccountCredentialId())) {
                StringCredentialsImpl generatedCredentials = new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        GENERATED_SERVICE_ACCOUNT_TOKEN_ID,
                        GENERATED_SERVICE_ACCOUNT_DESCRIPTION,
                        Secret.fromString(serviceAccountToken.get()));
                config.setServiceAccountCredential(generatedCredentials);
                config.setServiceAccountCredentialId(generatedCredentials.getId());
            }
        }


        return config;
    }

    private static Optional<String> getPropertyByEnvOrSystemProperty(String envVar, String systemProperty, EnvVars envVars) {
        String envResult = envVars.get(envVar);
        if (envResult != null) {
            return Optional.of(envResult);
        }

        String systemEnvResult = System.getenv(envVar);
        if (systemEnvResult != null) {
            return Optional.of(systemEnvResult);
        }

        String systemResult = System.getProperty(systemProperty);
        if (systemResult != null) {
            return Optional.of(systemResult);
        }

        return Optional.empty();
    }

    public static boolean isOPReference(String ref) {
        if (!ref.startsWith("op://")) {
            return false;
        }
        String[] parts = ref.substring("op://".length()).split("/");
        return parts.length >= 3 && parts.length <= 4;
    }
}
