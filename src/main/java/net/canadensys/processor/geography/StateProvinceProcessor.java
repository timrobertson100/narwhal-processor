package net.canadensys.processor.geography;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import net.canadensys.parser.StateProvinceNameParser;
import net.canadensys.processor.AbstractDataProcessor;
import net.canadensys.processor.ProcessingResult;
import net.canadensys.vocabulary.stateprovince.StateProvinceEnum;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;
import org.gbif.api.model.vocabulary.Country;
import org.gbif.common.parsers.ParseResult;
import org.gbif.common.parsers.ParseResult.CONFIDENCE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StateProvince processor to handle state or province names of a specific country.
 * You should reuse the same instance(one per country) to save resources.
 * @author canadensys
 *
 * @param <T> any enum that implements StateProvinceEnum
 * See : http://jtechies.blogspot.ca/2012/07/item-34-emulate-extensible-enums-with.html
 */
public class StateProvinceProcessor<T extends Enum<T> & StateProvinceEnum> extends AbstractDataProcessor {
	
	final Logger logger = LoggerFactory.getLogger(StateProvinceProcessor.class);
	
	protected static final String DEFAULT_STATEPROVINCE_NAME = "stateprovince";
	
	private StateProvinceNameParser<T> stateProvinceNameParser;
	
	private Country associatedCountry;
	protected String stateProvinceName = null;
	protected ErrorHandlingModeEnum errorHandlingMode;
	
	//Keep a reference to the fromCode(String) method
	protected Method fromCodeMethod;
	
	/**
	 * Constructor, default field names and ErrorHandlingModeEnum.USE_ORIGINAL will be used.
	 * NoSuchElementException could be thrown if something is wrong with the dictionary file or the StateProvinceEnum implementation.
	 * @param targetCountry used to retrieve the dictionary file
	 * @param stateProvince class of the concrete enum
	 */
	public StateProvinceProcessor(Country targetCountry, Class<T> stateProvinceClass){
		this(targetCountry,stateProvinceClass,DEFAULT_STATEPROVINCE_NAME,ErrorHandlingModeEnum.USE_ORIGINAL);
	}
	
	/**
	 * NoSuchElementException could be thrown if something is wrong with the dictionary file or the StateProvinceEnum implementation.
	 * NullPointerException will be thrown if the dictionary can not be found.
	 * @param targetCountry used to retrieve the dictionary file
	 * @param stateProvince class of the concrete enum
	 * @param stateProvinceName name of the field holding the stateprovince value
	 * @param errorHandlingMode
	 */
	public StateProvinceProcessor(Country targetCountry, Class<T> stateProvinceClass, String stateProvinceName, ErrorHandlingModeEnum errorHandlingMode){
		stateProvinceNameParser = new StateProvinceNameParser<T>(targetCountry,stateProvinceClass);
		this.associatedCountry = targetCountry;
		this.errorHandlingMode = errorHandlingMode;
		this.stateProvinceName = stateProvinceName;
		try {
			//make sure we can find the fromCode(String) method for this enum
			fromCodeMethod = stateProvinceClass.getMethod("fromCode", String.class);
		} catch (SecurityException e) {
			fromCodeMethod = null;
		} catch (NoSuchMethodException e) {
			fromCodeMethod = null;
		}
		
		if(fromCodeMethod == null){
			String errorText = "No well-formed StateProvinceEnum found for country " + targetCountry.getTitle() + " and Class " + stateProvinceClass;
			logger.error(errorText);
			throw new NoSuchElementException(errorText);
		}
		
		//always a default Locale
		setLocale(Locale.ENGLISH);
	}

	/**
	 * State/Province Bean processing function.
	 * @param in Java bean containing the raw stateprovince as String
	 * @param out Java bean containing the stateprovince (from controlled vocabulary) as String
	 * @param params Will be ignored so use null
	 * @param result Optional ProcessingResult
	 */
	@Override
	public void processBean(Object in, Object out, Map<String, Object> params,
			ProcessingResult result) {
		try {
			String textStateProvince = (String)PropertyUtils.getSimpleProperty(in, stateProvinceName);
			StateProvinceEnum stateProvinceEnum = process(textStateProvince,result);
			String stateProvinceStr = null;
			if(stateProvinceEnum == null){
				switch (errorHandlingMode) {
					case USE_ORIGINAL:stateProvinceStr=textStateProvince;
						break;
					case USE_NULL:stateProvinceStr=null;
						break;
					case USE_EMPTY:stateProvinceStr="";
						break;
					default:stateProvinceStr=null;
						break;
				}
			}
			else{
				stateProvinceStr = stateProvinceEnum.getName();
			}
			PropertyUtils.setSimpleProperty(out, stateProvinceName, stateProvinceStr);
		} catch (IllegalAccessException e) {
			logger.error("Bean access error", e);
		} catch (InvocationTargetException e) {
			logger.error("Bean access error", e);
		} catch (NoSuchMethodException e) {
			logger.error("Bean access error", e);
		}
	}
	
	@Override
	public boolean validateBean(Object in, boolean isMandatory, Map<String, Object> params, ProcessingResult result) {
		String textStateProvince = null;
		try {
			textStateProvince = (String)PropertyUtils.getSimpleProperty(in, stateProvinceName);
			if(process(textStateProvince,result) != null){
				return true;
			}
		//change to multiple Exception catch when moving to Java 7
		} catch (IllegalAccessException e) {
			logger.error("Bean access error", e);
			return false;
		} catch (InvocationTargetException e) {
			logger.error("Bean access error", e);
			return false;
		} catch (NoSuchMethodException e) {
			logger.error("Bean access error", e);
			return false;
		}
		
		//no valid country was found, check if this value was mandatory
		if(!isMandatory && StringUtils.isBlank(textStateProvince)){
			return true;
		}
		return false;
	}
	
	/**
	 * State/province processing function.
	 * This function will ignore errorHandlingMode since the return type is StateProvinceEnum.
	 * @param stateProvinceStr stateProvince string to be processed
	 * @param result optional ProcessingResult
	 * @return matching StateProvinceEnum or null
	 */
	public StateProvinceEnum process(String stateProvinceStr, ProcessingResult result){
		if(StringUtils.isBlank(stateProvinceStr)){
			return null;
		}
		ParseResult<String> parsingResult = stateProvinceNameParser.parse(stateProvinceStr);
		if(parsingResult.isSuccessful() && parsingResult.getConfidence().equals(CONFIDENCE.DEFINITE)){
			//using reflection to invoke fromCode(String), the cost is higher than calling directly
			//CanadaProvince.fromCode(String) but this will work for all StateProvinceEnum implementation
			try {
				StateProvinceEnum spe = (StateProvinceEnum)fromCodeMethod.invoke(null, parsingResult.getPayload());
				return spe;
			} catch (IllegalArgumentException e) {
				logger.error("StateProvinceEnum fromCode Method access error", e);
			} catch (IllegalAccessException e) {
				logger.error("StateProvinceEnum fromCode Method access error", e);
			} catch (InvocationTargetException e) {
				logger.error("StateProvinceEnum fromCode Method access error", e);
			}
		}
		else{
			if(result != null){
				result.addError(
						MessageFormat.format(resourceBundle.getString("stateProvince.error.notFound"),stateProvinceStr,associatedCountry.getTitle()));
			}
		}
		return null;
	}
	
	public Country getAssociatedCountry(){
		return associatedCountry;
	}
	
	@Override
	public ErrorHandlingModeEnum getErrorHandlingMode() {
		return errorHandlingMode;
	}
}
