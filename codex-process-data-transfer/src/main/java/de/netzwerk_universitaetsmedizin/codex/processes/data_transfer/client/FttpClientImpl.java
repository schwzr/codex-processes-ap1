package de.netzwerk_universitaetsmedizin.codex.processes.data_transfer.client;

import static de.netzwerk_universitaetsmedizin.codex.processes.data_transfer.ConstantsDataTransfer.PSEUDONYM_PATTERN_STRING;

import java.security.KeyStore;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;

public class FttpClientImpl implements FttpClient, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(FttpClientImpl.class);

	private static final Pattern DIC_PSEUDONYM_PATTERN = Pattern.compile(PSEUDONYM_PATTERN_STRING);

	private final IRestfulClientFactory clientFactory;
	private final String fttpServerBase;
	private final String fttpStudy;
	private final String fttpTarget;
	private final String fttpApiKey;

	public FttpClientImpl(KeyStore trustStore, KeyStore keyStore, char[] keyStorePassword, String fttpServerBase,
			String fttpApiKey, String fttpStudy, String fttpTarget)
	{
		clientFactory = createClientFactory(trustStore, keyStore, keyStorePassword);

		this.fttpServerBase = fttpServerBase;
		this.fttpApiKey = fttpApiKey;
		this.fttpStudy = fttpStudy;
		this.fttpTarget = fttpTarget;
	}

	protected ApacheRestfulClientFactoryWithTlsConfig createClientFactory(KeyStore trustStore, KeyStore keyStore,
			char[] keyStorePassword)
	{
		Objects.requireNonNull(trustStore, "trustStore");
		Objects.requireNonNull(keyStore, "keyStore");
		Objects.requireNonNull(keyStorePassword, "keyStorePassword");

		FhirContext fhirContext = FhirContext.forR4();
		ApacheRestfulClientFactoryWithTlsConfig hapiClientFactory = new ApacheRestfulClientFactoryWithTlsConfig(
				fhirContext, trustStore, keyStore, keyStorePassword);
		hapiClientFactory.setServerValidationMode(ServerValidationModeEnum.NEVER);
		fhirContext.setRestfulClientFactory(hapiClientFactory);
		return hapiClientFactory;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(fttpServerBase, "fttpServerBase");
		Objects.requireNonNull(fttpApiKey, "fttpApiKey");
		Objects.requireNonNull(fttpStudy, "fttpStudy");
		Objects.requireNonNull(fttpTarget, "fttpTarget");
	}

	@Override
	public Optional<String> getCrrPseudonym(String dicSourceAndPseudonym)
	{
		Objects.requireNonNull(dicSourceAndPseudonym, "dicSourceAndPseudonym");

		logger.info("Requesting CRR pseudonym from {} ...", dicSourceAndPseudonym);

		try
		{
			IGenericClient client = clientFactory.newGenericClient(fttpServerBase);
			client.registerInterceptor(new LoggingInterceptor());

			Parameters parameters = client.operation().onServer().named("request-psn-workflow")
					.withParameters(createParameters(dicSourceAndPseudonym)).accept(Constants.CT_FHIR_XML_NEW)
					.encoded(EncodingEnum.XML).execute();

			return getPseudonym(parameters);
		}
		catch (Exception e)
		{
			logger.error("Error while retrieving pseudonym", e);
			return Optional.empty();
		}
	}

	protected Parameters createParameters(String dicSourceAndPseudonym)
	{
		Matcher matcher = DIC_PSEUDONYM_PATTERN.matcher(dicSourceAndPseudonym);
		if (!matcher.matches())
			throw new IllegalArgumentException("DIC pseudonym not matching " + PSEUDONYM_PATTERN_STRING);

		String source = matcher.group(1);
		String original = matcher.group(2);

		Parameters p = new Parameters();
		p.addParameter("study", fttpStudy);
		p.addParameter("original", original);
		p.addParameter("source", source);
		p.addParameter("target", fttpTarget);
		p.addParameter("apikey", fttpApiKey);

		return p;
	}

	protected Optional<String> getPseudonym(Parameters params)
	{
		if (params == null)
			return Optional.empty();

		for (ParametersParameterComponent comp : params.getParameterFirstRep().getPart())
		{
			if ("pseudonym".equals(comp.getName()))
			{
				return Optional.of(comp.getValue()).filter(v -> v instanceof StringType).map(v -> (StringType) v)
						.map(StringType::getValue);
			}
		}

		return Optional.empty();
	}

	@Override
	public void testConnection()
	{
		IGenericClient client = clientFactory.newGenericClient(fttpServerBase);
		CapabilityStatement statement = client.capabilities().ofType(CapabilityStatement.class).execute();

		logger.info("Connection test OK {} - {}", statement.getSoftware().getName(),
				statement.getSoftware().getVersion());
	}
}
