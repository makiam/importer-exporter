/*
 * This file is part of the 3D City Database Importer/Exporter.
 * Copyright (c) 2007 - 2013
 * Institute for Geodesy and Geoinformation Science
 * Technische Universitaet Berlin, Germany
 * http://www.gis.tu-berlin.de/
 * 
 * The 3D City Database Importer/Exporter program is free software:
 * you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program. If not, see 
 * <http://www.gnu.org/licenses/>.
 * 
 * The development of the 3D City Database Importer/Exporter has 
 * been financially supported by the following cooperation partners:
 * 
 * Business Location Center, Berlin <http://www.businesslocationcenter.de/>
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * Berlin Senate of Business, Technology and Women <http://www.berlin.de/sen/wtf/>
 */
package org.citydb.modules.citygml.importer.database.content;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import org.citydb.api.geometry.GeometryObject;
import org.citydb.config.Config;
import org.citydb.database.TableEnum;
import org.citydb.log.Logger;
import org.citydb.modules.citygml.common.database.xlink.DBXlinkSurfaceGeometry;
import org.citydb.util.Util;
import org.citygml4j.geometry.Matrix;
import org.citygml4j.model.citygml.CityGMLClass;
import org.citygml4j.model.citygml.core.ImplicitGeometry;
import org.citygml4j.model.citygml.core.ImplicitRepresentationProperty;
import org.citygml4j.model.citygml.tunnel.AbstractBoundarySurface;
import org.citygml4j.model.citygml.tunnel.BoundarySurfaceProperty;
import org.citygml4j.model.citygml.tunnel.IntTunnelInstallation;
import org.citygml4j.model.citygml.tunnel.TunnelInstallation;
import org.citygml4j.model.gml.geometry.AbstractGeometry;
import org.citygml4j.model.gml.geometry.GeometryProperty;

public class DBTunnelInstallation implements DBImporter {
	private final Logger LOG = Logger.getInstance();

	private final Connection batchConn;
	private final DBImporterManager dbImporterManager;

	private PreparedStatement psTunnelInstallation;
	private DBCityObject cityObjectImporter;
	private DBTunnelThematicSurface thematicSurfaceImporter;
	private DBSurfaceGeometry surfaceGeometryImporter;
	private DBOtherGeometry otherGeometryImporter;
	private DBImplicitGeometry implicitGeometryImporter;

	private boolean affineTransformation;
	private int batchCounter;
	private int nullGeometryType;
	private String nullGeometryTypeName;

	public DBTunnelInstallation(Connection batchConn, Config config, DBImporterManager dbImporterManager) throws SQLException {
		this.batchConn = batchConn;
		this.dbImporterManager = dbImporterManager;

		affineTransformation = config.getProject().getImporter().getAffineTransformation().isSetUseAffineTransformation();
		init();
	}

	private void init() throws SQLException {
		nullGeometryType = dbImporterManager.getDatabaseAdapter().getGeometryConverter().getNullGeometryType();
		nullGeometryTypeName = dbImporterManager.getDatabaseAdapter().getGeometryConverter().getNullGeometryTypeName();

		StringBuilder stmt = new StringBuilder()
		.append("insert into TUNNEL_INSTALLATION (ID, OBJECTCLASS_ID, CLASS, CLASS_CODESPACE, FUNCTION, FUNCTION_CODESPACE, USAGE, USAGE_CODESPACE, TUNNEL_ID, TUNNEL_HOLLOW_SPACE_ID, ")
		.append("LOD2_BREP_ID, LOD3_BREP_ID, LOD4_BREP_ID, LOD2_OTHER_GEOM, LOD3_OTHER_GEOM, LOD4_OTHER_GEOM, ")
		.append("LOD2_IMPLICIT_REP_ID, LOD3_IMPLICIT_REP_ID, LOD4_IMPLICIT_REP_ID, ")
		.append("LOD2_IMPLICIT_REF_POINT, LOD3_IMPLICIT_REF_POINT, LOD4_IMPLICIT_REF_POINT, ")
		.append("LOD2_IMPLICIT_TRANSFORMATION, LOD3_IMPLICIT_TRANSFORMATION, LOD4_IMPLICIT_TRANSFORMATION) values ")
		.append("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
		psTunnelInstallation = batchConn.prepareStatement(stmt.toString());

		surfaceGeometryImporter = (DBSurfaceGeometry)dbImporterManager.getDBImporter(DBImporterEnum.SURFACE_GEOMETRY);
		otherGeometryImporter = (DBOtherGeometry)dbImporterManager.getDBImporter(DBImporterEnum.OTHER_GEOMETRY);
		implicitGeometryImporter = (DBImplicitGeometry)dbImporterManager.getDBImporter(DBImporterEnum.IMPLICIT_GEOMETRY);
		cityObjectImporter = (DBCityObject)dbImporterManager.getDBImporter(DBImporterEnum.CITYOBJECT);
		thematicSurfaceImporter = (DBTunnelThematicSurface)dbImporterManager.getDBImporter(DBImporterEnum.TUNNEL_THEMATIC_SURFACE);
	}

	public long insert(TunnelInstallation tunnelInstallation, CityGMLClass parent, long parentId) throws SQLException {
		long tunnelInstallationId = dbImporterManager.getDBId(DBSequencerEnum.CITYOBJECT_ID_SEQ);
		if (tunnelInstallationId == 0)
			return 0;

		// CityObject
		cityObjectImporter.insert(tunnelInstallation, tunnelInstallationId);

		// TunnelInstallation
		// ID
		psTunnelInstallation.setLong(1, tunnelInstallationId);

		// OBJECTCLASS_ID
		psTunnelInstallation.setLong(2, Util.cityObject2classId(tunnelInstallation.getCityGMLClass()));

		// tun:class
		if (tunnelInstallation.isSetClazz() && tunnelInstallation.getClazz().isSetValue()) {
			psTunnelInstallation.setString(3, tunnelInstallation.getClazz().getValue());
			psTunnelInstallation.setString(4, tunnelInstallation.getClazz().getCodeSpace());
		} else {
			psTunnelInstallation.setNull(3, Types.VARCHAR);
			psTunnelInstallation.setNull(4, Types.VARCHAR);
		}

		// tun:function
		if (tunnelInstallation.isSetFunction()) {
			String[] function = Util.codeList2string(tunnelInstallation.getFunction());
			psTunnelInstallation.setString(5, function[0]);
			psTunnelInstallation.setString(6, function[1]);
		} else {
			psTunnelInstallation.setNull(5, Types.VARCHAR);
			psTunnelInstallation.setNull(6, Types.VARCHAR);
		}

		// tun:usage
		if (tunnelInstallation.isSetUsage()) {
			String[] usage = Util.codeList2string(tunnelInstallation.getUsage());
			psTunnelInstallation.setString(7, usage[0]);
			psTunnelInstallation.setString(8, usage[1]);
		} else {
			psTunnelInstallation.setNull(7, Types.VARCHAR);
			psTunnelInstallation.setNull(8, Types.VARCHAR);
		}

		// parentId
		switch (parent) {
		case TUNNEL:
		case TUNNEL_PART:
			psTunnelInstallation.setLong(9, parentId);
			psTunnelInstallation.setNull(10, Types.NULL);
			break;
		case HOLLOW_SPACE:
			psTunnelInstallation.setNull(9, Types.NULL);
			psTunnelInstallation.setLong(10, parentId);
			break;
		default:
			psTunnelInstallation.setNull(9, Types.NULL);
			psTunnelInstallation.setNull(10, Types.NULL);
		}

		// Geometry
		for (int i = 0; i < 3; i++) {
			GeometryProperty<? extends AbstractGeometry> geometryProperty = null;
			long geometryId = 0;
			GeometryObject geometryObject = null;

			switch (i) {
			case 0:
				geometryProperty = tunnelInstallation.getLod2Geometry();
				break;
			case 1:
				geometryProperty = tunnelInstallation.getLod3Geometry();
				break;
			case 2:
				geometryProperty = tunnelInstallation.getLod4Geometry();
				break;
			}

			if (geometryProperty != null) {
				if (geometryProperty.isSetGeometry()) {
					AbstractGeometry abstractGeometry = geometryProperty.getGeometry();
					if (surfaceGeometryImporter.isSurfaceGeometry(abstractGeometry))
						geometryId = surfaceGeometryImporter.insert(abstractGeometry, tunnelInstallationId);
					else if (otherGeometryImporter.isPointOrLineGeometry(abstractGeometry))
						geometryObject = otherGeometryImporter.getPointOrCurveGeometry(abstractGeometry);
					else {
						StringBuilder msg = new StringBuilder(Util.getFeatureSignature(
								tunnelInstallation.getCityGMLClass(), 
								tunnelInstallation.getId()));
						msg.append(": Unsupported geometry type ");
						msg.append(abstractGeometry.getGMLClass()).append('.');

						LOG.error(msg.toString());
					}

					geometryProperty.unsetGeometry();
				} else {
					// xlink
					String href = geometryProperty.getHref();

					if (href != null && href.length() != 0) {
						dbImporterManager.propagateXlink(new DBXlinkSurfaceGeometry(
								href, 
								tunnelInstallationId, 
								TableEnum.TUNNEL_INSTALLATION, 
								"LOD" + (i + 2) + "_BREP_ID"));
					}
				}
			}

			if (geometryId != 0)
				psTunnelInstallation.setLong(11 + i, geometryId);
			else
				psTunnelInstallation.setNull(11 + i, Types.NULL);

			if (geometryObject != null)
				psTunnelInstallation.setObject(14 + i, dbImporterManager.getDatabaseAdapter().getGeometryConverter().getDatabaseObject(geometryObject, batchConn));
			else
				psTunnelInstallation.setNull(14 + i, nullGeometryType, nullGeometryTypeName);
		}

		// implicit geometry
		for (int i = 0; i < 3; i++) {
			ImplicitRepresentationProperty implicit = null;
			GeometryObject pointGeom = null;
			String matrixString = null;
			long implicitId = 0;

			switch (i) {
			case 0:
				implicit = tunnelInstallation.getLod2ImplicitRepresentation();
				break;
			case 1:
				implicit = tunnelInstallation.getLod3ImplicitRepresentation();
				break;
			case 2:
				implicit = tunnelInstallation.getLod4ImplicitRepresentation();
				break;
			}

			if (implicit != null) {
				if (implicit.isSetObject()) {
					ImplicitGeometry geometry = implicit.getObject();

					// reference Point
					if (geometry.isSetReferencePoint())
						pointGeom = otherGeometryImporter.getPoint(geometry.getReferencePoint());

					// transformation matrix
					if (geometry.isSetTransformationMatrix()) {
						Matrix matrix = geometry.getTransformationMatrix().getMatrix();
						if (affineTransformation)
							matrix = dbImporterManager.getAffineTransformer().transformImplicitGeometryTransformationMatrix(matrix);

						matrixString = Util.collection2string(matrix.toRowPackedList(), " ");
					}

					// reference to IMPLICIT_GEOMETRY
					implicitId = implicitGeometryImporter.insert(geometry, tunnelInstallationId);
				}
			}

			if (implicitId != 0)
				psTunnelInstallation.setLong(17 + i, implicitId);
			else
				psTunnelInstallation.setNull(17 + i, Types.NULL);

			if (pointGeom != null)
				psTunnelInstallation.setObject(20 + i, dbImporterManager.getDatabaseAdapter().getGeometryConverter().getDatabaseObject(pointGeom, batchConn));
			else
				psTunnelInstallation.setNull(20 + i, nullGeometryType, nullGeometryTypeName);

			if (matrixString != null)
				psTunnelInstallation.setString(23 + i, matrixString);
			else
				psTunnelInstallation.setNull(23 + i, Types.VARCHAR);
		}

		psTunnelInstallation.addBatch();
		if (++batchCounter == dbImporterManager.getDatabaseAdapter().getMaxBatchSize())
			dbImporterManager.executeBatch(DBImporterEnum.TUNNEL_INSTALLATION);

		// BoundarySurfaces
		if (tunnelInstallation.isSetBoundedBySurface()) {
			for (BoundarySurfaceProperty boundarySurfaceProperty : tunnelInstallation.getBoundedBySurface()) {
				AbstractBoundarySurface boundarySurface = boundarySurfaceProperty.getBoundarySurface();

				if (boundarySurface != null) {
					String gmlId = boundarySurface.getId();
					long id = thematicSurfaceImporter.insert(boundarySurface, tunnelInstallation.getCityGMLClass(), tunnelInstallationId);

					if (id == 0) {
						StringBuilder msg = new StringBuilder(Util.getFeatureSignature(
								tunnelInstallation.getCityGMLClass(), 
								tunnelInstallation.getId()));
						msg.append(": Failed to write ");
						msg.append(Util.getFeatureSignature(
								boundarySurface.getCityGMLClass(), 
								gmlId));

						LOG.error(msg.toString());
					}

					// free memory of nested feature
					boundarySurfaceProperty.unsetBoundarySurface();
				} else {
					// xlink
					String href = boundarySurfaceProperty.getHref();

					if (href != null && href.length() != 0) {
						LOG.error("XLink reference '" + href + "' to " + CityGMLClass.ABSTRACT_TUNNEL_BOUNDARY_SURFACE + " feature is not supported.");
					}
				}
			}
		}

		// insert local appearance
		cityObjectImporter.insertAppearance(tunnelInstallation, tunnelInstallationId);

		return tunnelInstallationId;
	}

	public long insert(IntTunnelInstallation intTunnelInstallation, CityGMLClass parent, long parentId) throws SQLException {
		long intTunnelInstallationId = dbImporterManager.getDBId(DBSequencerEnum.CITYOBJECT_ID_SEQ);
		if (intTunnelInstallationId == 0)
			return 0;

		// CityObject
		cityObjectImporter.insert(intTunnelInstallation, intTunnelInstallationId);

		// IntTunnelInstallation
		// ID
		psTunnelInstallation.setLong(1, intTunnelInstallationId);

		// OBJECTCLASS_ID
		psTunnelInstallation.setLong(2, Util.cityObject2classId(intTunnelInstallation.getCityGMLClass()));

		// class
		if (intTunnelInstallation.isSetClazz() && intTunnelInstallation.getClazz().isSetValue()) {
			psTunnelInstallation.setString(3, intTunnelInstallation.getClazz().getValue());
			psTunnelInstallation.setString(4, intTunnelInstallation.getClazz().getCodeSpace());
		} else {
			psTunnelInstallation.setNull(3, Types.VARCHAR);
			psTunnelInstallation.setNull(4, Types.VARCHAR);
		}

		// function
		if (intTunnelInstallation.isSetFunction()) {
			String[] function = Util.codeList2string(intTunnelInstallation.getFunction());
			psTunnelInstallation.setString(5, function[0]);
			psTunnelInstallation.setString(6, function[1]);
		} else {
			psTunnelInstallation.setNull(5, Types.VARCHAR);
			psTunnelInstallation.setNull(6, Types.VARCHAR);
		}

		// usage
		if (intTunnelInstallation.isSetUsage()) {
			String[] usage = Util.codeList2string(intTunnelInstallation.getUsage());
			psTunnelInstallation.setString(7, usage[0]);
			psTunnelInstallation.setString(8, usage[1]);
		} else {
			psTunnelInstallation.setNull(7, Types.VARCHAR);
			psTunnelInstallation.setNull(8, Types.VARCHAR);
		}

		// parentId
		switch (parent) {
		case TUNNEL:
		case TUNNEL_PART:
			psTunnelInstallation.setLong(9, parentId);
			psTunnelInstallation.setNull(10, Types.NULL);
			break;
		case HOLLOW_SPACE:
			psTunnelInstallation.setNull(9, Types.NULL);
			psTunnelInstallation.setLong(10, parentId);
			break;
		default:
			psTunnelInstallation.setNull(9, Types.NULL);
			psTunnelInstallation.setNull(10, Types.NULL);
		}	

		// Geometry
		psTunnelInstallation.setNull(11, Types.NULL);
		psTunnelInstallation.setNull(12, Types.NULL);
		psTunnelInstallation.setNull(14, nullGeometryType, nullGeometryTypeName);
		psTunnelInstallation.setNull(15, nullGeometryType, nullGeometryTypeName);

		long geometryId = 0;
		GeometryObject geometryObject = null;

		if (intTunnelInstallation.isSetLod4Geometry()) {
			GeometryProperty<? extends AbstractGeometry> geometryProperty = intTunnelInstallation.getLod4Geometry();

			if (geometryProperty.isSetGeometry()) {
				AbstractGeometry abstractGeometry = geometryProperty.getGeometry();
				if (surfaceGeometryImporter.isSurfaceGeometry(abstractGeometry))
					geometryId = surfaceGeometryImporter.insert(abstractGeometry, intTunnelInstallationId);
				else if (otherGeometryImporter.isPointOrLineGeometry(abstractGeometry))
					geometryObject = otherGeometryImporter.getPointOrCurveGeometry(abstractGeometry);
				else {
					StringBuilder msg = new StringBuilder(Util.getFeatureSignature(
							intTunnelInstallation.getCityGMLClass(), 
							intTunnelInstallation.getId()));
					msg.append(": Unsupported geometry type ");
					msg.append(abstractGeometry.getGMLClass()).append('.');

					LOG.error(msg.toString());
				}

				geometryProperty.unsetGeometry();
			} else {
				// xlink
				String href = geometryProperty.getHref();

				if (href != null && href.length() != 0) {
					dbImporterManager.propagateXlink(new DBXlinkSurfaceGeometry(
							href, 
							intTunnelInstallationId, 
							TableEnum.TUNNEL_INSTALLATION, 
							"LOD4_BREP_ID"));
				}
			}
		}

		if (geometryId != 0)
			psTunnelInstallation.setLong(13, geometryId);
		else
			psTunnelInstallation.setNull(13, Types.NULL);

		if (geometryObject != null)
			psTunnelInstallation.setObject(16, dbImporterManager.getDatabaseAdapter().getGeometryConverter().getDatabaseObject(geometryObject, batchConn));
		else
			psTunnelInstallation.setNull(16, nullGeometryType, nullGeometryTypeName);

		// implicit geometry
		psTunnelInstallation.setNull(17, Types.NULL);
		psTunnelInstallation.setNull(18, Types.NULL);
		psTunnelInstallation.setNull(20, nullGeometryType, nullGeometryTypeName);
		psTunnelInstallation.setNull(21, nullGeometryType, nullGeometryTypeName);
		psTunnelInstallation.setNull(23, Types.VARCHAR);
		psTunnelInstallation.setNull(24, Types.VARCHAR);

		GeometryObject pointGeom = null;
		String matrixString = null;
		long implicitId = 0;

		if (intTunnelInstallation.isSetLod4ImplicitRepresentation()) {
			ImplicitRepresentationProperty implicit = intTunnelInstallation.getLod4ImplicitRepresentation();

			if (implicit.isSetObject()) {
				ImplicitGeometry geometry = implicit.getObject();

				// reference Point
				if (geometry.isSetReferencePoint())
					pointGeom = otherGeometryImporter.getPoint(geometry.getReferencePoint());

				// transformation matrix
				if (geometry.isSetTransformationMatrix()) {
					Matrix matrix = geometry.getTransformationMatrix().getMatrix();
					if (affineTransformation)
						matrix = dbImporterManager.getAffineTransformer().transformImplicitGeometryTransformationMatrix(matrix);

					matrixString = Util.collection2string(matrix.toRowPackedList(), " ");
				}

				// reference to IMPLICIT_GEOMETRY
				implicitId = implicitGeometryImporter.insert(geometry, intTunnelInstallationId);
			}
		}

		if (implicitId != 0)
			psTunnelInstallation.setLong(19, implicitId);
		else
			psTunnelInstallation.setNull(19, Types.NULL);

		if (pointGeom != null)
			psTunnelInstallation.setObject(22, dbImporterManager.getDatabaseAdapter().getGeometryConverter().getDatabaseObject(pointGeom, batchConn));
		else
			psTunnelInstallation.setNull(22, nullGeometryType, nullGeometryTypeName);

		if (matrixString != null)
			psTunnelInstallation.setString(25, matrixString);
		else
			psTunnelInstallation.setNull(25, Types.VARCHAR);

		psTunnelInstallation.addBatch();
		if (++batchCounter == dbImporterManager.getDatabaseAdapter().getMaxBatchSize())
			dbImporterManager.executeBatch(DBImporterEnum.TUNNEL_INSTALLATION);

		// BoundarySurfaces
		if (intTunnelInstallation.isSetBoundedBySurface()) {
			for (BoundarySurfaceProperty boundarySurfaceProperty : intTunnelInstallation.getBoundedBySurface()) {
				AbstractBoundarySurface boundarySurface = boundarySurfaceProperty.getBoundarySurface();

				if (boundarySurface != null) {
					String gmlId = boundarySurface.getId();
					long id = thematicSurfaceImporter.insert(boundarySurface, intTunnelInstallation.getCityGMLClass(), intTunnelInstallationId);

					if (id == 0) {
						StringBuilder msg = new StringBuilder(Util.getFeatureSignature(
								intTunnelInstallation.getCityGMLClass(), 
								intTunnelInstallation.getId()));
						msg.append(": Failed to write ");
						msg.append(Util.getFeatureSignature(
								boundarySurface.getCityGMLClass(), 
								gmlId));

						LOG.error(msg.toString());
					}

					// free memory of nested feature
					boundarySurfaceProperty.unsetBoundarySurface();
				} else {
					// xlink
					String href = boundarySurfaceProperty.getHref();

					if (href != null && href.length() != 0) {
						LOG.error("XLink reference '" + href + "' to " + CityGMLClass.ABSTRACT_TUNNEL_BOUNDARY_SURFACE + " feature is not supported.");
					}
				}
			}
		}

		// insert local appearance
		cityObjectImporter.insertAppearance(intTunnelInstallation, intTunnelInstallationId);

		return intTunnelInstallationId;
	}

	@Override
	public void executeBatch() throws SQLException {
		psTunnelInstallation.executeBatch();
		batchCounter = 0;
	}

	@Override
	public void close() throws SQLException {
		psTunnelInstallation.close();
	}

	@Override
	public DBImporterEnum getDBImporterType() {
		return DBImporterEnum.TUNNEL_INSTALLATION;
	}

}