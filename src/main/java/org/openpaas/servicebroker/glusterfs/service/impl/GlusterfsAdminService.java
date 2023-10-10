package org.openpaas.servicebroker.glusterfs.service.impl;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import org.openpaas.servicebroker.common.HttpClientUtils;
import org.openpaas.servicebroker.common.JsonUtils;
import org.openpaas.servicebroker.exception.ServiceBrokerException;
import org.openpaas.servicebroker.exception.ServiceInstanceExistsException;
import org.openpaas.servicebroker.glusterfs.common.ConstantsAuthToken;
import org.openpaas.servicebroker.glusterfs.exception.GlusterfsServiceException;
import org.openpaas.servicebroker.glusterfs.model.GlusterfsServiceInstance;
import org.openpaas.servicebroker.model.CreateServiceInstanceBindingRequest;
import org.openpaas.servicebroker.model.CreateServiceInstanceRequest;
import org.openpaas.servicebroker.model.ServiceInstance;
import org.openpaas.servicebroker.model.ServiceInstanceBinding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

/**
 * Glusterfs대용량저장소를 조작하기위한 유틸리티 클래스.
 * 
 * @author 김한종
 *
 */
@PropertySource("classpath:glusterfs.properties")
@Service
public class GlusterfsAdminService {

	public static final String SERVICE_INSTANCES_FILDS = "instance_id, service_id, plan_id, organization_guid, space_guid, tenant_name, tenant_id";
	
	public static final String SERVICE_INSTANCES_FIND_BY_INSTANCE_ID = "select " + SERVICE_INSTANCES_FILDS + " from gfbroker.service_instances where instance_id = ?";
	
	public static final String SERVICE_GLUSTERFS_INSTANCES_FIND_BY_INSTANCE_ID = "select instance_id, tenant_name, tenant_id from gfbroker.service_instances where instance_id = ?";
	
	public static final String SERVICE_INSTANCES_DELETE_BY_INSTANCE_ID = "delete from gfbroker.service_instances where instance_id = ?";
	
	public static final String SERVICE_INSTANCES_UPSERT_FILDS = "ON DUPLICATE KEY UPDATE instance_id = ?, service_id = ?, plan_id = ?, organization_guid = ?, space_guid = ?, tenant_name = ?, tenant_id = ?";
	
	public static final String SERVICE_INSTANCES_ADD = "insert into gfbroker.service_instances("+SERVICE_INSTANCES_FILDS+") values(?,?,?,?,?,?,?) "+ SERVICE_INSTANCES_UPSERT_FILDS;
	
	public static final String SERVICE_INSTANCES_UPDATE_FILDS = "UPDATE gfbroker.service_instances SET service_id = ?, plan_id = ?, organization_guid = ?, space_guid = ? where instance_id = ?";
	
	public static final String SERVICE_BINDING_FILDS ="binding_id, instance_id, app_id, username, password";
	
	public static final String SERVICE_BINDING_UPSERT_FILDS = "ON DUPLICATE KEY UPDATE instance_id = ?, app_id = ? ,username = ?, password = ?";
	
	public static final String SERVICE_BINDING_ADD = "insert into gfbroker.service_binding("+SERVICE_BINDING_FILDS+") values(?,?,?,?,?) "+ SERVICE_BINDING_UPSERT_FILDS;
	
	public static final String SERVICE_BINDING_FIND_BY_BINDING_ID = "select " + SERVICE_BINDING_FILDS + " from gfbroker.service_binding where binding_id = ?";
	
	public static final String SERVICE_BINDING_FIND_BY_INSTANCE_ID = "select " + SERVICE_BINDING_FILDS + " from gfbroker.service_binding where instance_id = ?";
	
	public static final String SERVICE_BINDING_DELETE_BY_BINDING_ID = "delete from gfbroker.service_binding where binding_id = ?";
	
	public static final String SERVICE_BINDING_FIND_USERNAME_BY_BINDING_ID = "select username from gfbroker.service_binding where binding_id = ?";
	
	// Plan별 MAX_USER_CONNECTIONS 정보
	public static String planA = "ty8u76yi-b086-4a24-b041-0aeef1a819d1";
	public static int planAsize = 1024 * 1024 * 5;
	public static String planB = "sd456f21-9bc5-4a86-937f-e2c14bb9f497";
	public static int planBsize = 1024 * 1024 * 100;
	public static String planC = "koi908i7-9bc5-4a86-937f-e2c14bb9f497";
	public static int planCsize = 1024 * 1024 * 1000;
	
	static String TENANT_PREFIX = "op_";
	
	private Logger logger = LoggerFactory.getLogger(GlusterfsAdminService.class);
	
	@Autowired
	private Environment env;
	
	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	private final RowMapper<ServiceInstance> mapper = new ServiceInstanceRowMapper();
	
	private final RowMapper<GlusterfsServiceInstance> mapper3 = new GlusterfsServiceInstanceRowMapper();
	
	private final RowMapper<ServiceInstanceBinding> mapper2 = new ServiceInstanceBindingRowMapper();
	
	
	/**
	 * ServiceInstanceId로 ServiceInstance정보를 조회합니다.
	 * @param id
	 * @return
	 */
	public ServiceInstance findById(String id){
		logger.debug("GlusterfsAdminService.findById");
		ServiceInstance serviceInstance = null;;
		try {
			serviceInstance = jdbcTemplate.queryForObject(SERVICE_INSTANCES_FIND_BY_INSTANCE_ID, mapper, id);
			if (serviceInstance != null)
				serviceInstance.withDashboardUrl(getDashboardUrl(serviceInstance.getServiceInstanceId()));
		} catch (EmptyResultDataAccessException e) {
			logger.debug("[ap] GlusterfsAdminService.findById throw EmptyResultDataAccessException");
			return serviceInstance;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return serviceInstance;
	}
	
	/**
	 * ServiceInstanceId로 tenantInfo정보를 조회합니다.
	 * @param id
	 * @return
	 */
	public GlusterfsServiceInstance tenantInfofindById(String id){
		logger.debug("GlusterfsAdminService.tenantInfofindById");
		GlusterfsServiceInstance serviceInstance = null;;
		try {
			serviceInstance = jdbcTemplate.queryForObject(SERVICE_GLUSTERFS_INSTANCES_FIND_BY_INSTANCE_ID, mapper3, id);
		} catch (Exception e) {
		}
		return serviceInstance;
	}
	
	/**
	 * 요청 정보로 부터 ServiceInstance를 생성합니다.
	 * @param request
	 * @return
	 */
	public ServiceInstance createServiceInstanceByRequest(CreateServiceInstanceRequest request){
		logger.debug("GlusterfsAdminService.createServiceInstanceByRequest");
		return new ServiceInstance(request).withDashboardUrl(getDashboardUrl(request.getServiceInstanceId()));
	}
	
	/**
	 * ServiceInstanceBindingId로 ServiceInstanceBinding정보를 조회합니다.
	 * @param id
	 * @return
	 */
	public ServiceInstanceBinding findBindById(String id){
		logger.debug("GlusterfsAdminService.findBindById");
		ServiceInstanceBinding serviceInstanceBinding = null;;
		try {
			serviceInstanceBinding = jdbcTemplate.queryForObject(SERVICE_BINDING_FIND_BY_BINDING_ID, mapper2, id);
		} catch (Exception e) {
		}
		return serviceInstanceBinding;
	}
	
	
	/**
	 * ServiceInstanceId로 ServiceInstanceBinding 목록 정보를 조회합니다.
	 * @param id
	 * @return
	 */
	public List<Map<String,Object>> findBindByInstanceId(String id){
		logger.debug("GlusterfsAdminService.findBindByInstanceId");
		
		List<Map<String,Object>> list = new ArrayList<Map<String,Object>>();
		try {
			list = jdbcTemplate.queryForList(SERVICE_BINDING_FIND_BY_INSTANCE_ID, id);
		} catch (Exception e) {
		}
		return list;
	}
	
	/**
	 * 요청 정보로부터 ServiceInstanceBinding 정보를 생성합니다.
	 * @param request
	 * @return
	 */
	public ServiceInstanceBinding createServiceInstanceBindingByRequest(CreateServiceInstanceBindingRequest request){
		logger.debug("GlusterfsAdminService.createServiceInstanceBindingByRequest");
		
		return new ServiceInstanceBinding(request.getBindingId(), 
				request.getServiceInstanceId(), 
				new HashMap<String, Object>(), 
				"syslogDrainUrl", 
				request.getAppGuid());
	}
	
	/**
	 * ServiceInstance 정보를 저장합니다.
	 * @param serviceInstance
	 * @throws GlusterfsServiceException
	 */
	public void save(ServiceInstance serviceInstance, GlusterfsServiceInstance gf) throws GlusterfsServiceException{
		try{
			logger.debug("GlusterfsAdminService.save");
		        logger.debug("serviceInstance.getServiceInstanceId()="+serviceInstance.getServiceInstanceId());
		        logger.debug("serviceInstance.getServiceDefinitionId()="+serviceInstance.getServiceDefinitionId());
		        logger.debug("serviceInstance.getPlanId()="+serviceInstance.getPlanId());
		        logger.debug("serviceInstance.getOrganizationGuid()="+serviceInstance.getOrganizationGuid());
		        logger.debug("serviceInstance.getSpaceGuid()="+serviceInstance.getSpaceGuid());
		        logger.debug("gf.getTenantName()="+gf.getTenantName());
		        logger.debug("gf.getTenantId()="+gf.getTenantId());
		        logger.debug("serviceInstance.getServiceInstanceId()="+serviceInstance.getServiceInstanceId());
		        logger.debug("serviceInstance.getServiceDefinitionId()="+serviceInstance.getServiceDefinitionId());
		        logger.debug("serviceInstance.getPlanId()="+serviceInstance.getPlanId());
		        logger.debug("serviceInstance.getOrganizationGuid()="+serviceInstance.getOrganizationGuid());
		        logger.debug("serviceInstance.getSpaceGuid()="+serviceInstance.getSpaceGuid());
		        logger.debug("gf.getTenantName()="+gf.getTenantName());
		        logger.debug("gf.getTenantId()="+gf.getTenantId());
			jdbcTemplate.update(SERVICE_INSTANCES_ADD, 
					serviceInstance.getServiceInstanceId(),
					serviceInstance.getServiceDefinitionId(),
					serviceInstance.getPlanId(),
					serviceInstance.getOrganizationGuid(),
					serviceInstance.getSpaceGuid(),
					gf.getTenantName(),
					gf.getTenantId(),
					serviceInstance.getServiceInstanceId(),
					serviceInstance.getServiceDefinitionId(),
					serviceInstance.getPlanId(),
					serviceInstance.getOrganizationGuid(),
					serviceInstance.getSpaceGuid(),
					gf.getTenantName(),
					gf.getTenantId());
		} catch (Exception e) {
			throw handleException(e);
		}
	}
	
	/**
	 * ServiceInstanceId로 ServiceInstance정보를 삭제합니다.
	 * @param id
	 * @throws GlusterfsServiceException
	 */
	public void delete(String id) throws GlusterfsServiceException{
		try{
			logger.debug("GlusterfsAdminService.delete");
			
			jdbcTemplate.update(SERVICE_INSTANCES_DELETE_BY_INSTANCE_ID, id);
		} catch (Exception e) {
			throw handleException(e);
		}
	}
	
	/**
	 * 해당하는 Database를 삭제합니다.
	 * @param serviceInstance
	 * @throws GlusterfsServiceException
	 */
	public void deleteTenant(ServiceInstance serviceInstance) throws ServiceBrokerException{
		
		
		GlusterfsServiceInstance gfInstance =  tenantInfofindById(serviceInstance.getServiceInstanceId());
		
		if(null == gfInstance) throw new ServiceBrokerException("");
		
		try{
			logger.debug("GlusterfsAdminService.deleteDatabase");
			
			HttpHeaders headers = new HttpHeaders();	
			if (!isValidToken())
				setGlusterfsAuthToken();
			headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
			String body = 	"";
			HttpEntity<String> entity = new HttpEntity<String>(body, headers);
			ResponseEntity<String> response = null;
			
			try{
					
				String url = env.getRequiredProperty("glusterfs.endpoint") + env.getRequiredProperty("glusterfs.uri.deletetenant");

				url = url.replace("#TENANT_ID", gfInstance.getTenantId());
				
				response = HttpClientUtils.send(url, entity, HttpMethod.DELETE);
				
				if (response.getStatusCode() != HttpStatus.NO_CONTENT) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

			} catch (Exception e) {
				e.printStackTrace();
				if (e.getMessage().equals("401 Unauthorized")) {
					setGlusterfsAuthToken();
					deleteTenant(serviceInstance);
				}else{// if(e.getMessage().equals("API Platform Error: API Platform Server Not Found")){
					throw new ServiceBrokerException("Tennant exception occurred during deletion.");
				}
			}
		} catch (Exception e) {
			throw handleException(e);
		}
	}

    /**
     * Glusterfs 관리자 AuthToken Valid check
     * @throws GlusterfsServiceException
     */
    public boolean isValidToken(){
        boolean isValid = false;

        ZonedDateTime  currentDateTime = ZonedDateTime.now(ZoneId.of(ConstantsAuthToken.GLUSTERFS_TIMEZONE));
        //ZonedDateTime  currentDateTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        if (!StringUtils.isEmpty(ConstantsAuthToken.AUTH_TOKEN)
                && ConstantsAuthToken.ISSUED_AT != null && ConstantsAuthToken.EXPIRES_AT != null
                && currentDateTime.isBefore(ConstantsAuthToken.EXPIRES_AT))
            isValid = true;

        return isValid;
    }

	/**
	 * Glusterfs 관리자 AuthToken 획득
	 * @throws GlusterfsServiceException
	 */
	public void setGlusterfsAuthToken() throws GlusterfsServiceException{
		
		logger.debug("GlusterfsAdminService.getGlusterfsAuthToken");
		
		HttpHeaders headers = new HttpHeaders();	
		headers.setContentType(MediaType.APPLICATION_JSON);
                String body ="{ " +
			    "\"auth\": { " +
			    "\"identity\": { "+
			            "\"methods\": [ "+
			                "\"password\" ], "+
			            "\"password\": { "+
		        	        "\"user\": { "+
		                	    "\"name\": \""+ env.getRequiredProperty("glusterfs.username") +"\","+
			                    "\"domain\": { "+
        		        	        "\"name\": \""+ env.getRequiredProperty("glusterfs.domainname") +"\" "+
		        	            "},"+
                			    "\"password\": \""+ env.getRequiredProperty("glusterfs.password") +"\" "+
		                	"} "+
		        	    "} "+
			        "} "+                     
			    "} "+
			"}";

		logger.debug("[ap] body : " + body);
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;
		
		try {
            String url = env.getRequiredProperty("glusterfs.authurl") + env.getRequiredProperty("glusterfs.uri.auth");
            logger.debug("[ap] url : " + url);

            response = HttpClientUtils.send(url, entity, HttpMethod.POST);

            if (response.getStatusCode() != HttpStatus.OK && response.getStatusCode() != HttpStatus.CREATED)
                throw new ServiceBrokerException("Response code is " + response.getStatusCode());

            //JsonNode json = JsonUtils.convertToJson(response);
            //logger.debug("[ap] json: " + json.toString());

            //setAuthToken(json);
            if (response.getHeaders().containsKey("X-Subject-Token")){
                ConstantsAuthToken.AUTH_TOKEN = response.getHeaders().get("X-Subject-Token").get(0);
                setAuthToken(JsonUtils.convertToJson(response));
            }
		} catch (Exception e) {
			e.printStackTrace();
			throw handleException(e);
		}
	}

	/**
	 * Glusterfs 관리자 AuthToken 설정
	 * @param json
	 */
	private void setAuthToken(JsonNode json) throws ParseException {
		logger.debug("json : " + json.toString());

		//String authToken = json.get("token").get("id").asText();
		//logger.debug("authToken : " + authToken);
		//ConstantsAuthToken.AUTH_TOKEN = authToken;

		String issued = json.get("token").get("issued_at").asText();
		String expires = json.get("token").get("expires_at").asText();

        ZonedDateTime issued_at = ZonedDateTime.parse(issued); //DateTimeFormatter
        ZonedDateTime expires_at = ZonedDateTime.parse(expires);

        ConstantsAuthToken.GLUSTERFS_TIMEZONE = env.getRequiredProperty("glusterfs.timezone");
        ConstantsAuthToken.ISSUED_AT = issued_at;
        ConstantsAuthToken.EXPIRES_AT = expires_at;

        logger.debug("[ap] setAuthToken ISSUED_AT: " + ConstantsAuthToken.ISSUED_AT );
        logger.debug("[ap] setAuthToken EXPIRES_AT : " + ConstantsAuthToken.EXPIRES_AT );
	}
	
	/**
	 * Glusterfs Quota 설정
	 * @param planId
	 * @param tenantId
	 * @throws ServiceBrokerException
	 */
	public void setGlusterfsQuota(String planId, String tenantId) throws ServiceBrokerException{
		
		logger.debug("GlusterfsAdminService.setGlusterfsQuota");
		HttpHeaders headers = new HttpHeaders();	
		headers.setContentType(MediaType.APPLICATION_JSON);
		SimpleDateFormat formatter= new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z");
		Date date = new Date(System.currentTimeMillis());
		if (!isValidToken())
			setGlusterfsAuthToken();
		headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
		logger.debug("planId="+planId);
		if(planId.equals(planA)) {
			headers.set("X-Container-Meta-Quota-Bytes", planAsize+"");
		}
		else if(planId.equals(planB)) {
			headers.set("X-Container-Meta-Quota-Bytes", planBsize+"");
		}
		else if(planId.equals(planC)) {
			headers.set("X-Container-Meta-Quota-Bytes", planCsize+"");
		}
		else{
			throw new ServiceBrokerException("");
		}
		String body = 	"";
		
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;

		try{
				
			String url = env.getRequiredProperty("glusterfs.swiftproxy") + env.getRequiredProperty("glusterfs.uri.account");
			logger.debug("url : " + url);
			url = url.replace("#TENANT_ID", tenantId);
                        logger.debug("header[X-Container-Meta-Quota-Bytes] : " + headers.get("X-Container-Meta-Quota-Bytes").get(0));

			response = HttpClientUtils.send(url, entity, HttpMethod.PUT);
			logger.debug("[ap] response.getStatusCode() = "+response.getStatusCode()+" = ");

			if (response.getStatusCode() != HttpStatus.ACCEPTED && response.getStatusCode() != HttpStatus.CREATED) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

		} catch (Exception e) {
			e.printStackTrace();
			if (e.getMessage().equals("403 Forbidden")) {
				setGlusterfsAuthToken();
				setGlusterfsQuota(planId, tenantId);
			}else { 
				throw new GlusterfsServiceException("Tennant exception occurred during update.");
			}
		}
	}

	/**
	 * 사용자 명으로 아이디 검색
	 * @param username
	 * @return
	 * @throws GlusterfsServiceException
	 */
	public String getGlusterfsUserIdByUserName(String username) throws GlusterfsServiceException{
		
		logger.debug("GlusterfsAdminService.getGlusterfsAuthToken");
		HttpHeaders headers = new HttpHeaders();	
		if (!isValidToken())
			setGlusterfsAuthToken();
		headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
		String body = 	"";
		String userId = "";
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;
		logger.debug("body : " + body);
		
		try{
				
			String url = env.getRequiredProperty("glusterfs.endpoint") + env.getRequiredProperty("glusterfs.uri.userinfo");

			url = url.replace("#USER_NAME", username);
			
			response = HttpClientUtils.send(url, entity, HttpMethod.GET);
			
			if (response.getStatusCode() != HttpStatus.OK) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

			JsonNode json = JsonUtils.convertToJson(response);
			
			userId = getUserId(json);
			
		} catch (Exception e) {
			if (e.getMessage().equals("401 Unauthorized")) {
				logger.debug("setGlusterfsAuthToken");
				setGlusterfsAuthToken();
				userId = getGlusterfsUserIdByUserName(username);
			}else { 
				throw new GlusterfsServiceException("UserInfo exception occurred during search.");
			}
		}
		return userId;
	}

	/**
	 * 사용자 아이디 추출
	 * @param json
	 * @return
	 */
	private String getUserId(JsonNode json) {
		logger.debug("getUserId() json : " + json.toString());
		
		String userId = json.get("users").get(0).get("id").asText();
		
		return userId;
		
	}

	/**
	 * ROLE 아이디 추출
	 * @param json
	 * @return
	 */
	private String getRoleId(JsonNode json) {
		logger.debug("json : " + json.toString());
		logger.debug("json : " + json.get("roles").get(0));
		logger.debug("json : " + json.get("roles").get(0).get("id"));

		String roleId = json.get("roles").get(0).get("id").asText();

		return roleId;

	}
	
	/**
	 * Glusterfs Tenant 생성
	 * @param serviceInstance
	 * @return
	 * @throws ServiceInstanceExistsException
	 * @throws ServiceBrokerException
	 */
	public GlusterfsServiceInstance createTenant(ServiceInstance serviceInstance) throws ServiceInstanceExistsException, ServiceBrokerException{
		
		logger.debug("GlusterfsAdminService.createTenant");
		
		HttpHeaders headers = new HttpHeaders();	
		headers.setContentType(MediaType.APPLICATION_JSON);
		logger.debug("[ap] AUTH_TOKEN1 :" + ConstantsAuthToken.AUTH_TOKEN +"=");

        if (!isValidToken())
			setGlusterfsAuthToken();
		logger.debug("[ap] AUTH_TOKEN2 : " + ConstantsAuthToken.AUTH_TOKEN);
		headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
		String body = 	"{" +
				"\"project\": " +
				    "{" + 
					"\"name\": \"" + getTenantName(serviceInstance.getServiceInstanceId()) + "\"," + 
					"\"description\": \"Glusterfs Service\"," + 
					"\"enabled\": true" + 
					"}" + 
			"}";
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;
		logger.debug("createTenant() body : " + body);

		GlusterfsServiceInstance glusterfsServiceInstance = null;
		
		try{
				
			String url = env.getRequiredProperty("glusterfs.endpoint") + env.getRequiredProperty("glusterfs.uri.createtenant");
			logger.debug("createTenant() url : " + url);
			if (serviceInstance != null)
				logger.debug("[ap] planId="+serviceInstance.getPlanId()+"=");
			if (glusterfsServiceInstance != null)
				logger.debug("[ap] planId="+serviceInstance.getPlanId()+"=tenantId="+ glusterfsServiceInstance.getTenantId()+"=");

			response = HttpClientUtils.send(url, entity, HttpMethod.POST);

			JsonNode json = JsonUtils.convertToJson(response);
			logger.debug("[ap] json ="+json +"=");

			glusterfsServiceInstance = new GlusterfsServiceInstance(serviceInstance);
	            	logger.debug("[ap] getTenantId=" + glusterfsServiceInstance.getTenantId()+"=getServiceInstanceId="+glusterfsServiceInstance.getServiceInstanceId());
			setTenantInfo(json,glusterfsServiceInstance);
            		logger.debug("[ap] getTenantId=" + glusterfsServiceInstance.getTenantId()+"=getServiceInstanceId="+glusterfsServiceInstance.getServiceInstanceId());

            		setGlusterfsQuota(serviceInstance.getPlanId(), glusterfsServiceInstance.getTenantId());
			
			if (response.getStatusCode() != HttpStatus.OK && response.getStatusCode() != HttpStatus.CREATED) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

		} catch (ServiceBrokerException e) {
			e.printStackTrace();
			if (e.getMessage().equals("401 Unauthorized")) {
				setGlusterfsAuthToken();
				glusterfsServiceInstance = createTenant(serviceInstance);
			}else if(e.getMessage().equals("409 Conflict")){
				throw new ServiceInstanceExistsException(serviceInstance);
			}else { 
				throw new ServiceBrokerException("Tennant exception occurred during creation.");
			}
			
		} catch (Exception e) {
			e.printStackTrace();
		} 
		
		return glusterfsServiceInstance;
	}
	
	private void setTenantInfo(JsonNode json, GlusterfsServiceInstance glusterfsServiceInstance) {
		logger.debug("setTenantInfo() json : " + json.toString());
		glusterfsServiceInstance.setTenantId(json.get("project").get("id").asText());
		glusterfsServiceInstance.setTenantName(json.get("project").get("name").asText());
		
	}

	/**
	 * keystone에 정의된 role ID 를 조회합니다.
	 * @param roleName
	 * @throws GlusterfsServiceException
	 */
	public String getGlusterfsRolesIdByRoleName(String roleName) throws GlusterfsServiceException{
		logger.debug("GlusterfsAdminService.getRoles");
		String roleId = "";
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (!isValidToken())
			setGlusterfsAuthToken();
		headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
		String body = 	"";
		logger.debug("body : " + body);
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;

		try{
			// glusterfs.uri.roleinfo=/v3/roles?name=#ROLE_NAME
			String url = env.getRequiredProperty("glusterfs.endpoint") + env.getRequiredProperty("glusterfs.uri.roleinfo");
			url = url.replace("#ROLE_NAME", roleName);
			logger.debug("url : " + url);

			response = HttpClientUtils.send(url, entity, HttpMethod.GET);

			if (response.getStatusCode() != HttpStatus.OK) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

			JsonNode json = JsonUtils.convertToJson(response);

			roleId = getRoleId(json);

		} catch (ServiceBrokerException e) {
			e.printStackTrace();
			if (e.getMessage().equals("401 Unauthorized")) {
				setGlusterfsAuthToken();
			}else if(e.getMessage().equals("409 Conflict")){
				throw new GlusterfsServiceException("Tennant exception occurred during creation.(duplicated)");
			}else {
				throw new GlusterfsServiceException("Tennant exception occurred during creation.");
			}

		} catch (Exception e) {
			e.printStackTrace();
			throw handleException(e);
		}
		return roleId;
	}

	/**
	 * 해당하는 User에게 role 을 할당합니다.
	 * @param tenantId
	 * @param userId
	 * @throws GlusterfsServiceException
	 */
	public void assignRole(String tenantId, String userId) throws GlusterfsServiceException{
		logger.debug("GlusterfsAdminService.assignRole");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (!isValidToken())
			setGlusterfsAuthToken();
		headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
		String body = 	"";
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;
		logger.debug("body : " + body);

		try{
			//glusterfs.uri.assignrole=/v3/projects/#TENANT_ID/users/#USER_ID/roles/#ROLE_ID
			String roleId = getGlusterfsRolesIdByRoleName(env.getRequiredProperty("glusterfs.rolename"));
			String url = env.getRequiredProperty("glusterfs.endpoint") + env.getRequiredProperty("glusterfs.uri.assignrole");
			url = url.replace("#TENANT_ID", tenantId);
			url = url.replace("#USER_ID", userId);
			url = url.replace("#ROLE_ID", roleId);
			logger.debug("url : " + url);

			response = HttpClientUtils.send(url, entity, HttpMethod.PUT);

			if (response.getStatusCode() != HttpStatus.NO_CONTENT) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

		} catch (ServiceBrokerException e) {
			e.printStackTrace();
			if (e.getMessage().equals("401 Unauthorized")) {
				setGlusterfsAuthToken();
			}else if(e.getMessage().equals("409 Conflict")){
				throw new GlusterfsServiceException("Tennant exception occurred during creation.(duplicated)");
			}else {
				throw new GlusterfsServiceException("Tennant exception occurred during creation.");
			}

		} catch (Exception e) {
			e.printStackTrace();
			throw handleException(e);
		}
	}
	
	/**
	 * ServiceInstance의 Plan 정보를 수정합니다.
	 * @param instance
	 * @param request
	 * @throws GlusterfsServiceException
	 */
	public void updatePlan(ServiceInstance instance, ServiceInstance request) throws GlusterfsServiceException{
		try{
			logger.debug("GlusterfsAdminService.updatePlan");
			jdbcTemplate.update(SERVICE_INSTANCES_UPDATE_FILDS,
					instance.getServiceDefinitionId(),
					request.getPlanId(),
					instance.getOrganizationGuid(),
					instance.getSpaceGuid(),
					instance.getServiceInstanceId());
		} catch (Exception e) {
			throw handleException(e);
		}
	}
	
	/**
	 * ServiceInstanceBinding 정보를 저장합니다.
	 * @param serviceInstanceBinding
	 * @throws GlusterfsServiceException
	 */
	public void saveBind(ServiceInstanceBinding serviceInstanceBinding) throws GlusterfsServiceException{
		try{
			logger.debug("GlusterfsAdminService.saveBind");
			jdbcTemplate.update(SERVICE_BINDING_ADD, 
					serviceInstanceBinding.getId(),
					serviceInstanceBinding.getServiceInstanceId(),
					serviceInstanceBinding.getAppGuid(),
					serviceInstanceBinding.getCredentials().get("username"),
					serviceInstanceBinding.getCredentials().get("password"),
					serviceInstanceBinding.getServiceInstanceId(),
					serviceInstanceBinding.getAppGuid(),
					serviceInstanceBinding.getCredentials().get("username"),
					serviceInstanceBinding.getCredentials().get("password"));
		} catch (Exception e) {
			throw handleException(e);
		}
	}
	
	/**
	 * ServiceInstanceBindingId로 ServiceInstanceBinding 정보를 삭제합니다.
	 * @param id
	 * @throws GlusterfsServiceException
	 */
	public void deleteBind(String id) throws GlusterfsServiceException{
		try{
			logger.debug("GlusterfsAdminService.deleteBind");
			jdbcTemplate.update(SERVICE_BINDING_DELETE_BY_BINDING_ID, id);
		} catch (Exception e) {
			throw handleException(e);
		}
	}
	
	/**
	 * 해당하는 User를 생성합니다.
	 * @param userId
	 * @param password
	 * @throws GlusterfsServiceException
	 */
	public void createUser(String tenantId, String userId, String password) throws GlusterfsServiceException{
		logger.debug("GlusterfsAdminService.createUser");
		HttpHeaders headers = new HttpHeaders();	
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (!isValidToken())
			setGlusterfsAuthToken();
		headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
		String body = 	"{" +
					    	"\"user\": {" +
					    		"\"name\": \"" + userId + "\"," +
						        "\"email\": \"\"," +
						        "\"enabled\": true," +
						        "\"password\": \"" + password + "\"," +
						        "\"default_project_id\" : \"" + tenantId + "\"" +
						     "}" +
						"}";
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;
		logger.debug("createUser() body : " + body);
		
		try{
				
			String url = env.getRequiredProperty("glusterfs.endpoint") + env.getRequiredProperty("glusterfs.uri.createusers");
			logger.debug("[ap] createUser() url : " + url);
			logger.debug("[ap] ConstantsAuthToken.AUTH_TOKEN="+ConstantsAuthToken.AUTH_TOKEN+"= headers[X-Auth-Token]="+ entity.getHeaders().get("X-Auth-Token").get(0)+"=");
			
			response = HttpClientUtils.send(url, entity, HttpMethod.POST);
			
			if (response.getStatusCode() != HttpStatus.OK && response.getStatusCode() != HttpStatus.CREATED) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

		} catch (ServiceBrokerException e) {
			e.printStackTrace();
			if (e.getMessage().equals("401 Unauthorized")) {
				setGlusterfsAuthToken();
				createUser(tenantId, userId, password);
			}else if(e.getMessage().equals("409 Conflict")){
				throw new GlusterfsServiceException("Tennant exception occurred during creation.(duplicated)");
			}else { 
				throw new GlusterfsServiceException("Tennant exception occurred during creation.");
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			throw handleException(e);
		} 
	}
	
	//public void deleteUser(String database, String userId) throws GlusterfsServiceException{
	/**
	 * 해당하는 User를 삭제합니다.
	 * @param bindingId
	 * @throws GlusterfsServiceException
	 */
	public void deleteUser(String instanceId, String bindingId) throws GlusterfsServiceException{
		logger.debug("GlusterfsAdminService.createTenant");
		
		String userId = getGlusterfsUserIdByUserName(getUsername(bindingId));
		
		HttpHeaders headers = new HttpHeaders();	
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		if (!isValidToken())
			setGlusterfsAuthToken();
		headers.set("X-Auth-Token", ConstantsAuthToken.AUTH_TOKEN);
		String body = 	"";
		HttpEntity<String> entity = new HttpEntity<String>(body, headers);
		ResponseEntity<String> response = null;
		logger.debug("deleteUser() body : " + body);
		
		try{
				
			String url = env.getRequiredProperty("glusterfs.endpoint") + env.getRequiredProperty("glusterfs.uri.deleteusers");
			logger.debug("deleteuser() url : " + url);
			logger.debug("deleteUser() userId : " + userId);
			url = url.replace("#USER_ID", userId);
			response = HttpClientUtils.send(url, entity, HttpMethod.DELETE);
			
			if (response.getStatusCode() != HttpStatus.NO_CONTENT) throw new ServiceBrokerException("Response code is " + response.getStatusCode());

		} catch (ServiceBrokerException e) {
			e.printStackTrace();
			if (e.getMessage().equals("401 Unauthorized")) {
				setGlusterfsAuthToken();
				deleteUser(instanceId, bindingId);
			}else { 
				throw new GlusterfsServiceException("Tennant exception occurred during creation.");
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			throw handleException(e);
		} 
	}
	
	private GlusterfsServiceException handleException(Exception e) {
		logger.warn(e.getLocalizedMessage(), e);
		return new GlusterfsServiceException(e.getLocalizedMessage());
	}
	
	// DashboardUrl 생성
	public String getDashboardUrl(String instanceId){
		
		return "http://www.sample.com/"+instanceId;
	}
	
	// Tenant명 생성
	public String getTenantName(String id){
		
		String database;
		String s = id.replaceAll("-", "_");
	    database = TENANT_PREFIX+getUsername(s);
	    
	  return database;
	}
	
	// User명 생성
	public String getUsername(String id) {
		MessageDigest digest = null;
		try {
			digest = MessageDigest.getInstance("MD5");
		} catch (Exception e) {
			// TODO: handle exception
		}
		digest.update(id.getBytes());
		String username = new BigInteger(1, digest.digest()).toString(16).replaceAll("/[^a-zA-Z0-9]+/", "").substring(0, 16);
		return username;
						
	}
	
	private static final class ServiceInstanceRowMapper implements RowMapper<ServiceInstance> {
        @Override
        public ServiceInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
            CreateServiceInstanceRequest request = new CreateServiceInstanceRequest();
            request.withServiceInstanceId(rs.getString(1));
            request.setServiceDefinitionId(rs.getString(2));
            request.setPlanId(rs.getString(3));
            request.setOrganizationGuid(rs.getString(4));
            request.setSpaceGuid(rs.getString(5));
            return new ServiceInstance(request);
        }
    }
	
	private static final class GlusterfsServiceInstanceRowMapper implements RowMapper<GlusterfsServiceInstance> {
        @Override
        public GlusterfsServiceInstance mapRow(ResultSet rs, int rowNum) throws SQLException {
        	GlusterfsServiceInstance instance = new GlusterfsServiceInstance();
        	instance.setServiceInstanceId(rs.getString(1));
        	instance.setTenantName(rs.getString(2));
        	instance.setTenantId(rs.getString(3));
            return instance;
        }
    }
	
	private static final class ServiceInstanceBindingRowMapper implements RowMapper<ServiceInstanceBinding> {
        @Override
        public ServiceInstanceBinding mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ServiceInstanceBinding(rs.getString(1), 
            		rs.getString(2), 
            		new HashMap<String, Object>(), 
            		"", 
            		rs.getString(3));
        }
    }
	
	
}
