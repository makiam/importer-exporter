package de.tub.citydb.db.exporter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.citygml4j.geometry.BoundingVolume;
import org.citygml4j.model.citygml.CityGMLClass;

import de.tub.citydb.concurrent.WorkerPool;
import de.tub.citydb.config.Config;
import de.tub.citydb.config.project.database.Database;
import de.tub.citydb.config.project.filter.FilterConfig;
import de.tub.citydb.db.DBConnectionPool;
import de.tub.citydb.db.DBTableEnum;
import de.tub.citydb.event.EventDispatcher;
import de.tub.citydb.event.statistic.StatusDialogMessage;
import de.tub.citydb.filter.ExportFilter;
import de.tub.citydb.filter.feature.BoundingBoxFilter;
import de.tub.citydb.filter.feature.FeatureClassFilter;
import de.tub.citydb.filter.feature.GmlIdFilter;
import de.tub.citydb.filter.feature.GmlNameFilter;
import de.tub.citydb.filter.statistic.FeatureCounterFilter;
import de.tub.citydb.gui.ImpExpGui;
import de.tub.citydb.log.Logger;
import de.tub.citydb.util.DBUtil;
import de.tub.citydb.util.Util;

public class DBSplitter {
	private final Logger LOG = Logger.getInstance();

	private final DBConnectionPool dbConnectionPool;
	private final WorkerPool<DBSplittingResult> dbWorkerPool;
	private final ExportFilter exportFilter;
	private final Config config;
	private final EventDispatcher eventDispatcher;
	private volatile boolean shouldRun = true;

	private Connection connection;
	private long elementCounter;

	private Long firstElement;
	private Long lastElement;
	private String gmlIdFilter;
	private String gmlNameFilter;
	private String bboxFilter;

	private FeatureClassFilter featureClassFilter;
	private FeatureCounterFilter featureCounterFilter;
	private GmlIdFilter featureGmlIdFilter;
	private GmlNameFilter featureGmlNameFilter;
	private BoundingBoxFilter boundingBoxFilter;

	private FilterConfig filterConfig;

	public DBSplitter(DBConnectionPool dbConnectionPool, 
			WorkerPool<DBSplittingResult> dbWorkerPool, 
			ExportFilter exportFilter, 
			EventDispatcher eventDispatcher, 
			Config config) throws SQLException {
		this.dbConnectionPool = dbConnectionPool;
		this.dbWorkerPool = dbWorkerPool;
		this.exportFilter = exportFilter;
		this.eventDispatcher = eventDispatcher;
		this.config = config;

		init();
	}

	private void init() throws SQLException {
		connection = dbConnectionPool.getConnection();
		connection.setAutoCommit(false);

		// try and change workspace for connection if needed
		Database database = config.getProject().getDatabase();
		dbConnectionPool.changeWorkspace(
				connection, 
				database.getWorkspace().getExportWorkspace(),
				database.getWorkspace().getExportDate());

		// set filter instances 
		featureClassFilter = exportFilter.getFeatureClassFilter();
		featureCounterFilter = exportFilter.getFeatureCounterFilter();
		featureGmlIdFilter = exportFilter.getGmlIdFilter();
		featureGmlNameFilter = exportFilter.getGmlNameFilter();
		boundingBoxFilter = exportFilter.getBoundingBoxFilter();

		filterConfig = config.getProject().getExporter().getFilter();
	}

	private void initFilter() throws SQLException {
		// feature counter filter
		List<Long> counterFilterState = featureCounterFilter.getFilterState();
		firstElement = counterFilterState.get(0);
		lastElement = counterFilterState.get(1);

		// gml:id filter
		List<String> gmlIdList = featureGmlIdFilter.getFilterState();
		if (gmlIdList != null && !gmlIdList.isEmpty()) {
			String gmlIdFilterString = Util.collection2string(gmlIdList, "', '");
			gmlIdFilter = "co.GMLID in ('" + gmlIdFilterString + "')";
		}

		// gml:name filter
		gmlNameFilter = featureGmlNameFilter.getFilterState();
		if (gmlNameFilter != null)
			gmlNameFilter = gmlNameFilter.toUpperCase();

		// bounding box filter
		BoundingVolume bbox = boundingBoxFilter.getFilterState();
		config.getInternal().setUseInternalBBoxFilter(false);
		if (bbox != null) {
			DBUtil dbUtil = DBUtil.getInstance(dbConnectionPool);

			// check whether spatial indexes are active
			if (dbUtil.isIndexed("CITYOBJECT", "ENVELOPE")) {			
				String dbSrid = config.getInternal().getDbSrid();

				double minX = bbox.getLowerCorner().getX();
				double minY = bbox.getLowerCorner().getY();
				double maxX = bbox.getUpperCorner().getX();
				double maxY = bbox.getUpperCorner().getY();

				String mask = (filterConfig.getComplexFilter().getBoundingBoxFilter().isSetContainMode()) ? 
						"INSIDE+CONTAINS+EQUAL" :
							"INSIDE+CONTAINS+EQUAL+COVERS+COVEREDBY+OVERLAPBDYINTERSECT";

				bboxFilter = "SDO_RELATE(co.ENVELOPE, MDSYS.SDO_GEOMETRY(3003, " + dbSrid + ", NULL, " +
				"MDSYS.SDO_ELEM_INFO_ARRAY(1, 1003, 3), " +
				"MDSYS.SDO_ORDINATE_ARRAY(" + minX + ", " + minY + ", 0, " + maxX + ", " + maxY + ", 0)), " +
				"'querytype=WINDOW mask=" + mask + "') = 'TRUE'";

			} else {
				LOG.debug("Bounding box filter is enabled although spatial indexes are deactivated.");
				LOG.debug("Filtering will not be performed using spatial database operations.");
				config.getInternal().setUseInternalBBoxFilter(true);
			}
		}
	}

	public void shutdown() {
		shouldRun = false;
	}

	public void startQuery() throws SQLException {
		try {
			initFilter();
			queryCityObject();

			try {
				dbWorkerPool.join();
			} catch (InterruptedException e) {
				//
			}

			if (!featureClassFilter.filter(CityGMLClass.CITYOBJECTGROUP)) {
				queryCityObjectGroups();

				if (shouldRun) {
					try {
						dbWorkerPool.join();
					} catch (InterruptedException e) {
						//
					}
				}
			}

			if (config.getProject().getExporter().getAppearances().isSetExportAppearance())
				queryGlobalAppearance();
	
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (SQLException sqlEx) {
					//
				}

				connection = null;
			}
		}
	}

	private void queryCityObject() throws SQLException {
		Statement stmt = null;
		ResultSet rs = null;

		List<String> queryList = new ArrayList<String>();

		// build query strings...
		if (gmlNameFilter != null) {
			String tableName = null;
			String additionalWhere = null;

			for (CityGMLClass featureClass : featureClassFilter.getNotFilterState()) {
				switch (featureClass) {
				case BUILDING:
					tableName = DBTableEnum.BUILDING.toString();
					break;
				case CITYFURNITURE:
					tableName = DBTableEnum.CITY_FURNITURE.toString();
					break;
				case LANDUSE:
					tableName = DBTableEnum.LAND_USE.toString();
					break;
				case WATERBODY:
					tableName = DBTableEnum.WATERBODY.toString();
					break;
				case PLANTCOVER:
					tableName = DBTableEnum.PLANT_COVER.toString();
					break;
				case SOLITARYVEGETATIONOBJECT:
					tableName = DBTableEnum.SOLITARY_VEGETAT_OBJECT.toString();
					break;
				case TRANSPORTATIONCOMPLEX:
				case ROAD:
				case RAILWAY:
				case TRACK:
				case SQUARE:
					tableName = DBTableEnum.TRANSPORTATION_COMPLEX.toString();
					additionalWhere = "co.CLASS_ID=" + Util.cityObject2classId(featureClass);
					break;
				case RELIEFFEATURE:
					tableName = DBTableEnum.RELIEF_FEATURE.toString();
					additionalWhere = "co.CLASS_ID=" + Util.cityObject2classId(featureClass);
					break;
				case GENERICCITYOBJECT:
					tableName = DBTableEnum.GENERIC_CITYOBJECT.toString();
					break;
				default:
					continue;
				}

				String query = "select co.ID, co.CLASS_ID from CITYOBJECT co " +
				"inner join " + tableName + " j on co.ID=j.ID where ";

				if (bboxFilter != null)
					query += bboxFilter + " AND ";

				if (additionalWhere != null)
					query += additionalWhere + " AND ";

				query += "upper(j.NAME) like '%" + gmlNameFilter + "%' order by co.ID asc";

				queryList.add(query);
			}

		} else {
			String query = "select co.ID, co.CLASS_ID from CITYOBJECT co where ";

			if (filterConfig.isSetSimple()) {
				query += "co.CLASS_ID <> 23 ";

				if (gmlIdFilter != null)
					query += "AND " + gmlIdFilter;

				queryList.add(query);
			} else {			
				List<Integer> classIds = new ArrayList<Integer>();
				List<CityGMLClass> allowedFeature = featureClassFilter.getNotFilterState();
				for (CityGMLClass featureClass : allowedFeature) {
					if (featureClass == CityGMLClass.CITYOBJECTGROUP)
						continue;

					classIds.add(Util.cityObject2classId(featureClass));
				}

				if (!classIds.isEmpty()) {
					String classIdQuery = Util.collection2string(classIds, ", ");

					if (bboxFilter != null)
						query += bboxFilter + " AND ";

					query += "co.CLASS_ID in (" + classIdQuery +") "; 

					if (gmlIdFilter != null)
						query += "AND " + gmlIdFilter + " ";

					query += "order by co.ID asc";
					queryList.add(query);
				}				
			}			
		}

		if (queryList.size() == 0)
			return;

		try {

			for (String query : queryList) {
				stmt = connection.createStatement();
				rs = stmt.executeQuery(query);

				while (rs.next() && shouldRun) {
					elementCounter++;

					if (firstElement != null && elementCounter < firstElement)
						continue;

					if (lastElement != null && elementCounter > lastElement)
						break;

					long primaryKey = rs.getLong(1);
					int classId = rs.getInt(2);
					CityGMLClass cityObjectType = Util.classId2cityObject(classId);

					// set initial context...
					DBSplittingResult splitter = new DBSplittingResult(primaryKey, cityObjectType);
					dbWorkerPool.addWork(splitter);
				}

				rs.close();
			}
		} catch (SQLException sqlEx) {
			System.out.println(sqlEx);
			throw sqlEx;
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				rs = null;
			}

			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				stmt = null;
			}
		}
	}

	private void queryCityObjectGroups() throws SQLException {
		if (!shouldRun)
			return;

		LOG.info("Processing CityObjectGroup features.");
		eventDispatcher.triggerEvent(new StatusDialogMessage(ImpExpGui.labels.getString("export.dialog.group.msg")));

		Statement stmt = null;
		ResultSet rs = null;

		String query = "select co.ID from CITYOBJECT co where co.CLASS_ID=23 ";
		List<Integer> classIds = new ArrayList<Integer>();

		if (filterConfig.isSetSimple()) {
			if (gmlIdFilter != null)
				query += "AND " + gmlIdFilter;
		} else {
			if (gmlNameFilter != null)
				query = "select co.ID from CITYOBJECT co " +
				"inner join CITYOBJECTGROUP j on co.ID=j.ID where " +
				"upper(j.NAME) like '%" + gmlNameFilter + "%' ";

			if (bboxFilter != null)
				query += "AND " + bboxFilter;

			List<CityGMLClass> allowedFeature = featureClassFilter.getNotFilterState();
			for (CityGMLClass featureClass : allowedFeature) {
				if (featureClass == CityGMLClass.CITYOBJECTGROUP)
					continue;

				classIds.add(Util.cityObject2classId(featureClass));
			}
		}

		try {
			stmt = connection.createStatement();
			rs = stmt.executeQuery(query);
			List<Long> groupIds = new ArrayList<Long>();

			while (rs.next() && shouldRun) {	
				long groupId = rs.getLong(1);
				elementCounter++;

				if (firstElement != null && elementCounter < firstElement)
					continue;

				if (lastElement != null && elementCounter > lastElement)
					break;

				groupIds.add(groupId);				
			}

			rs.close();

			Set<Long> visited = new HashSet<Long>();
			for (Long groupId : groupIds) {
				if (visited.contains(groupId))
					continue;

				visited.add(groupId);
				recursiveGroupMemberQuery(groupId, visited, classIds);				
			}			

		} catch (SQLException sqlEx) {
			LOG.error("SQL error: " + sqlEx.getMessage());
			throw sqlEx;
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				rs = null;
			}

			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				stmt = null;
			}
		}
	}

	private void recursiveGroupMemberQuery(long groupId, Set<Long> visited, List<Integer> classIds) throws SQLException {
		if (!shouldRun)
			return;

		Statement stmt = null;
		ResultSet rs = null;

		try {
			stmt = connection.createStatement();

			// first: check nested groups being group members
			String innerGroupQuery = "select co.ID from GROUP_TO_CITYOBJECT gtc " +
			"inner join CITYOBJECT co on co.ID=gtc.CITYOBJECT_ID " +
			"where gtc.CITYOBJECTGROUP_ID=" + groupId + " and co.CLASS_ID=23 ";

			if (bboxFilter != null)
				innerGroupQuery += "and " + bboxFilter;

			rs = stmt.executeQuery(innerGroupQuery);

			List<Long> groupIds = new ArrayList<Long>();			
			while (rs.next() && shouldRun) {
				long innerGroupId = rs.getLong(1);				
				groupIds.add(innerGroupId);
			}

			rs.close();

			for (Long innerGroupId : groupIds) {
				if (visited.contains(innerGroupId))
					continue;

				visited.add(innerGroupId);
				recursiveGroupMemberQuery(innerGroupId, visited, classIds);
			}

			// classIds filter
			String classIdsString = "";
			if (!classIds.isEmpty())
				classIdsString = "and co.CLASS_ID in (" + Util.collection2string(classIds, ",") + ")";

			// second: work on groupMembers which are not groups
			String groupMemberQuery = "select co.ID, co.CLASS_ID from CITYOBJECT co " +
			"inner join GROUP_TO_CITYOBJECT gtc on gtc.CITYOBJECT_ID=co.ID " +
			"where gtc.CITYOBJECTGROUP_ID=" + groupId;

			if (bboxFilter != null)
				groupMemberQuery += " and " + bboxFilter;

			groupMemberQuery += " and not co.CLASS_ID=23 " + classIdsString;

			rs = stmt.executeQuery(groupMemberQuery);

			while (rs.next() && shouldRun) {				
				long memberId = rs.getLong(1);
				int memberClassId = rs.getInt(2);
				CityGMLClass cityObjectType = Util.classId2cityObject(memberClassId);

				// set initial context...
				DBSplittingResult splitter = new DBSplittingResult(memberId, cityObjectType);
				splitter.setCheckIfAlreadyExported(true);
				dbWorkerPool.addWork(splitter);
			} 

			rs.close();

			// third: work on parents which are not groups
			String parentQuery = "select grp.PARENT_CITYOBJECT_ID, co.CLASS_ID from CITYOBJECTGROUP grp " +
			"inner join CITYOBJECT co on co.ID=grp.PARENT_CITYOBJECT_ID " +
			"where grp.ID=" + groupId + " AND not grp.PARENT_CITYOBJECT_ID is NULL";

			if (bboxFilter != null)
				parentQuery += " and " + bboxFilter;

			parentQuery += " and not co.CLASS_ID=23 " + classIdsString;			

			rs = stmt.executeQuery(parentQuery);

			while (rs.next() && shouldRun) {				
				long memberId = rs.getLong(1);
				int memberClassId = rs.getInt(2);
				CityGMLClass cityObjectType = Util.classId2cityObject(memberClassId);

				// set initial context...
				DBSplittingResult splitter = new DBSplittingResult(memberId, cityObjectType);
				splitter.setCheckIfAlreadyExported(true);
				dbWorkerPool.addWork(splitter);
			}

			rs.close();

			// wait for jobs to be done...
			try {
				dbWorkerPool.join();
			} catch (InterruptedException e) {
				//
			}			

			// finally export group itself
			DBSplittingResult splitter = new DBSplittingResult(groupId, CityGMLClass.CITYOBJECTGROUP);
			splitter.setCheckIfAlreadyExported(true);
			dbWorkerPool.addWork(splitter);

		} catch (SQLException sqlEx) {
			LOG.error("SQL error: " + sqlEx.getMessage());
			throw sqlEx;
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				rs = null;
			}

			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				stmt = null;
			}
		}

	}

	private void queryGlobalAppearance() throws SQLException {
		if (!shouldRun)
			return;

		// there are two possible approaches for global appearances.
		// 1. (followed here)
		// iterate over all appearances that are not bound to a specific cityobject. if
		// a surface data member of that appearance is pointing to a geometry object
		// held in the gmlIdCache, then the appearance has to be written. this approach
		// might be slow if we have a large number of global appearances
		// 2.
		// drain all geometry entries held in the gmlIdCache to the database. on the
		// database level make a big join with the temporary cache table
		// (using dynamic_sampling for the temp table) and all relevant appearance tables.
		// the result is a list of those appearances that have to be written. this might
		// be slow if we have a big cache size but only a small number of global appearances

		LOG.info("Processing global appearance features.");
		eventDispatcher.triggerEvent(new StatusDialogMessage(ImpExpGui.labels.getString("export.dialog.globalApp.msg")));

		Statement stmt = null;
		ResultSet rs = null;

		try {
			stmt = connection.createStatement();
			String query = "select ID from APPEARANCE where CITYOBJECT_ID is NULL";
			rs = stmt.executeQuery(query);

			while (rs.next() && shouldRun) {
				long id = rs.getLong(1);

				// set initial context
				DBSplittingResult splitter = new DBSplittingResult(id, CityGMLClass.APPEARANCE);
				dbWorkerPool.addWork(splitter);
			}

		} catch (SQLException sqlEx) {
			LOG.error("SQL error: " + sqlEx.getMessage());
			throw sqlEx;
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				rs = null;
			}

			if (stmt != null) {
				try {
					stmt.close();
				} catch (SQLException sqlEx) {
					throw sqlEx;
				}

				stmt = null;
			}
		}
	}
}
