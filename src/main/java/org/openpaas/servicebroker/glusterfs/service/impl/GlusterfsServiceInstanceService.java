package org.openpaas.servicebroker.glusterfs.service.impl;


import org.openpaas.servicebroker.exception.ServiceBrokerException;
import org.openpaas.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.openpaas.servicebroker.exception.ServiceInstanceExistsException;
import org.openpaas.servicebroker.exception.ServiceInstanceUpdateNotSupportedException;
import org.openpaas.servicebroker.glusterfs.model.GlusterfsServiceInstance;
import org.openpaas.servicebroker.model.CreateServiceInstanceRequest;
import org.openpaas.servicebroker.model.DeleteServiceInstanceRequest;
import org.openpaas.servicebroker.model.ServiceInstance;
import org.openpaas.servicebroker.model.UpdateServiceInstanceRequest;
import org.openpaas.servicebroker.service.CatalogService;
import org.openpaas.servicebroker.service.ServiceInstanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 서비스 인스턴스 서비스가 제공해야하는 메소드를 정의한 인터페이스 클래스인 ServiceInstance를 상속하여 
 * Glusterfs 서비스 인스턴스 서비스 관련 메소드를 구현한 클래스. 
 * 서비스 인스턴스 생성/삭제/수정/조회 를 구현한다.
 *  
 * @author 김한종
 *
 */
@Service
public class GlusterfsServiceInstanceService implements ServiceInstanceService {

	private static final Logger logger = LoggerFactory.getLogger(GlusterfsServiceInstanceService.class);

	@Autowired
	private GlusterfsAdminService glusterfsAdminService;
	
	@Autowired
	private CatalogService service;
	
	@Autowired
	public GlusterfsServiceInstanceService(GlusterfsAdminService glusterfsAdminService) {
		this.glusterfsAdminService = glusterfsAdminService;
	}
	
	/**
	 * Provision(create)
	 */
	@Override
	public ServiceInstance createServiceInstance(CreateServiceInstanceRequest request) 
			throws ServiceInstanceExistsException, ServiceBrokerException {
		System.out.println("GlusterfsServiceInstanceService CLASS createServiceInstance");
		logger.debug("loggerGlusterfsServiceInstanceService CLASS createServiceInstance");

		/* 최초 ServiceInstance 생성 요청시에는 해당 ServiceInstance가 존재하지 않아 해당 메소드를 주석처리 하였습니다.*/
		ServiceInstance findInstance = glusterfsAdminService.findById(request.getServiceInstanceId());
		logger.debug("[paasta] 55 findInstance=", findInstance);

		// 요청 정보로부터 ServiceInstance정보를 생성합니다.
		ServiceInstance instance = null;
		if (findInstance == null) {
			instance = glusterfsAdminService.createServiceInstanceByRequest(request);
			if (instance == null )
				logger.debug("[paasta] 62 instance=", instance);
			else
				logger.debug("[paasta] 64 instance=", instance.getServiceInstanceId());
		}
		if(findInstance != null){
			if(findInstance.getServiceInstanceId().equals(instance.getServiceInstanceId()) &&
					findInstance.getPlanId().equals(instance.getPlanId()) &&
					findInstance.getServiceDefinitionId().equals(instance.getServiceDefinitionId())){
				findInstance.setHttpStatusOK();
				return findInstance;
			}else{
				throw new ServiceInstanceExistsException(instance);
			}
		}
		
		// Database를 생성합니다.
		GlusterfsServiceInstance gf = glusterfsAdminService.createTenant(instance);
		logger.debug("[paasta] 79 gf.ServiceInstanceId="+gf.getServiceInstanceId()+"=gf.TenantId="+gf.getTenantId());
		
		// ServiceInstance 정보를 저장합니다.
		glusterfsAdminService.save(instance, gf);
		
		return instance;
	}
	
	/**
	 * Provision(delete)
	 */
	@Override
	public ServiceInstance deleteServiceInstance(DeleteServiceInstanceRequest request) throws ServiceBrokerException {
		
		// ServiceInstanceId로 ServiceInstance 정보를 조회합니다.
		ServiceInstance instance = glusterfsAdminService.findById(request.getServiceInstanceId());
		
		// 조회된 ServiceInstance가 없을경우 예외처리
		if(instance == null) return null;
		
		// 조회된 ServiceInstance정보로 해당 Database를 삭제합니다
		glusterfsAdminService.deleteTenant(instance);
		// 조회된 ServiceInstance정보로 해당 ServiceInstance정보를 삭제합니다
		glusterfsAdminService.delete(instance.getServiceInstanceId());
		
		return instance;		
	}

	/**
	 * Provision(update)
	 */
	@Override
	public ServiceInstance updateServiceInstance(UpdateServiceInstanceRequest request)
			throws ServiceInstanceUpdateNotSupportedException, ServiceBrokerException, ServiceInstanceDoesNotExistException {
		
		// ServiceInstanceId로 ServiceInstance 정보를 조회합니다.
		ServiceInstance instance = glusterfsAdminService.findById(request.getServiceInstanceId());
		
		// ServiceInstance가 없을경우 예외처리
		if(instance == null) throw new ServiceInstanceDoesNotExistException(request.getServiceInstanceId());
		
		// 요청 정보로부터 새로운 ServiceInstance정보를 생성합니다.
		ServiceInstance updatedInstance = new ServiceInstance(request);

		/* 기존 ServiceInstance의 Plan에 변견될경우 다음 처리를 수행합니다. */
		if(!instance.getPlanId().equals(updatedInstance.getPlanId())){
			// Plan 정보에 따라 해당 Database 사용자의 MAX_USER_CONNECTIONS 정보를 조정합니다.
			try {
				GlusterfsServiceInstance gf = glusterfsAdminService.tenantInfofindById(updatedInstance.getServiceInstanceId());
				glusterfsAdminService.setGlusterfsQuota(updatedInstance.getPlanId(), gf.getTenantId());
				//glusterfsAdminService.setUserConnections(updatedInstance.getPlanId(), instance.getServiceInstanceId());
			} catch (Exception e) {
				throw new ServiceInstanceUpdateNotSupportedException(e.getMessage());
			}
			
			// ServiceInstance의 Plan 정보를 수정합니다.
			glusterfsAdminService.updatePlan(instance, updatedInstance);
		}
		return updatedInstance;
	}
	
	/**
	 * Provision Info
	 */
	@Override
	public ServiceInstance getServiceInstance(String id) {
		return glusterfsAdminService.findById(id);
	}
	
}
