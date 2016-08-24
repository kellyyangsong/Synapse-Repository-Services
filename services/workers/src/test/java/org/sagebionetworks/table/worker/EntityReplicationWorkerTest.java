package org.sagebionetworks.table.worker;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.LinkedList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.table.cluster.ConnectionFactory;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.common.collect.Lists;


public class EntityReplicationWorkerTest {
	
	@Mock
	NodeDAO mockNodeDao;
	@Mock
	ConnectionFactory mockConnectionFactory;
	@Mock
	TableIndexDAO mockIndexDao;
	
	EntityReplicationWorker worker;
	
	List<ChangeMessage> changes;

	@Before
	public void before(){
		MockitoAnnotations.initMocks(this);
		worker = new EntityReplicationWorker();
		ReflectionTestUtils.setField(worker, "nodeDao", mockNodeDao);
		ReflectionTestUtils.setField(worker, "connectionFactory", mockConnectionFactory);
		
		ChangeMessage update = new ChangeMessage();
		update.setChangeType(ChangeType.UPDATE);
		update.setObjectType(ObjectType.ENTITY);
		update.setObjectId("111");
		ChangeMessage create = new ChangeMessage();
		create.setChangeType(ChangeType.CREATE);
		create.setObjectType(ObjectType.ENTITY);
		create.setObjectId("222");
		ChangeMessage delete = new ChangeMessage();
		delete.setChangeType(ChangeType.DELETE);
		delete.setObjectType(ObjectType.ENTITY);
		delete.setObjectId("333");
		changes = Lists.newArrayList(update, create, delete);
		
		when(mockConnectionFactory.getAllConnections()).thenReturn(Lists.newArrayList(mockIndexDao));
	}
	
	@Test
	public void testGroupByChangeType(){
		List<String> createOrUpdateIds = new LinkedList<>();
		List<String> deleteIds = new LinkedList<String>();
		EntityReplicationWorker.groupByChangeType(changes, createOrUpdateIds, deleteIds);
		List<String> expectedCreateOrUpdate = Lists.newArrayList("111","222");
		List<String> expectedDelete = Lists.newArrayList("333");
		assertEquals(expectedCreateOrUpdate, createOrUpdateIds);
		assertEquals(expectedDelete, deleteIds);
	}

}
