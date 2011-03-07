package de.tub.citydb.gui.panel.settings;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import de.tub.citydb.config.Config;
import de.tub.citydb.config.project.exporter.ExpXLinkConfig;
import de.tub.citydb.config.project.exporter.ExpXLinkFeatureConfig;
import de.tub.citydb.config.project.exporter.ExpXLinkMode;
import de.tub.citydb.gui.ImpExpGui;
import de.tub.citydb.gui.util.GuiUtil;
import de.tub.citydb.util.UUIDManager;

public class ExpXLinkPanel extends PrefPanelBase {
	private JPanel block1;
	private JPanel block1_1;
	private JPanel block2;
	private JPanel block2_1;
	
	private JRadioButton xlinkToFeature;
	private JRadioButton copyFeature;
	private JLabel featureIdPrefixLabel;
	private JTextField featureIdPrefix;	
	private JCheckBox featureAppendId;
	private JCheckBox featureKeepExtRef;
	private JRadioButton xlinkToGeometry;
	private JRadioButton copyGeometry;
	private JLabel geometryIdPrefixLabel;
	private JTextField geometryIdPrefix;
	private JCheckBox geometryAppendId;
	
	public ExpXLinkPanel(Config config) {
		super(config);
		initGui();
	}
	
	public boolean isModified() {
		if (super.isModified()) return true;
		
		ExpXLinkFeatureConfig feature = config.getProject().getExporter().getXlink().getFeature();
		ExpXLinkConfig geometry = config.getProject().getExporter().getXlink().getGeometry();

		if (!featureIdPrefix.getText().equals(feature.getIdPrefix())) return true;
		if (xlinkToFeature.isSelected() != feature.isModeXLink()) return true;
		if (copyFeature.isSelected() != feature.isModeCopy()) return true;
		if (featureAppendId.isSelected() != feature.isSetAppendId()) return true;
		if (featureKeepExtRef.isSelected() != feature.isSetKeepGmlIdAsExternalReference()) return true;
		
		if (!geometryIdPrefix.getText().equals(geometry.getIdPrefix())) return true;
		if (xlinkToGeometry.isSelected() != geometry.isModeXLink()) return true;
		if (copyGeometry.isSelected() != geometry.isModeCopy()) return true;
		if (geometryAppendId.isSelected() != geometry.isSetAppendId()) return true;
		
		return false;
	}
	
	public void initGui() {
		xlinkToFeature = new JRadioButton("");
		copyFeature = new JRadioButton("");
		ButtonGroup featureHandling = new ButtonGroup();
		featureHandling.add(xlinkToFeature);
		featureHandling.add(copyFeature);
		featureIdPrefixLabel = new JLabel("");
		featureIdPrefix = new JTextField("");
		featureAppendId = new JCheckBox("");
		featureKeepExtRef = new JCheckBox("");
	
		xlinkToGeometry = new JRadioButton("");
		copyGeometry = new JRadioButton("");
		ButtonGroup geometryHandling = new ButtonGroup();
		geometryHandling.add(xlinkToGeometry);
		geometryHandling.add(copyGeometry);
		geometryIdPrefixLabel = new JLabel("");
		geometryIdPrefix = new JTextField("");
		geometryAppendId = new JCheckBox("");
		
		setLayout(new GridBagLayout());
		{
			block1 = new JPanel();
			block1_1 = new JPanel();
			block2 = new JPanel();
			block2_1 = new JPanel();
			
			add(block1, GuiUtil.setConstraints(0,0,1.0,0.0,GridBagConstraints.BOTH,5,0,5,0));
			block1.setBorder(BorderFactory.createTitledBorder(""));
			block1.setLayout(new GridBagLayout());
			int lmargin = (int)(copyFeature.getPreferredSize().getWidth()) + 11;
			xlinkToFeature.setIconTextGap(10);
			copyFeature.setIconTextGap(10);
			{
				block1.add(xlinkToFeature, GuiUtil.setConstraints(0,0,1.0,1.0,GridBagConstraints.BOTH,0,5,0,5));
				block1.add(copyFeature, GuiUtil.setConstraints(0,1,1.0,1.0,GridBagConstraints.BOTH,0,5,0,5));
				block1.add(block1_1, GuiUtil.setConstraints(0,2,1.0,1.0,GridBagConstraints.BOTH,0,lmargin,5,5));
				
				block1_1.setLayout(new GridBagLayout());
				block1_1.setBorder(BorderFactory.createEmptyBorder());
				{
					block1_1.add(featureIdPrefixLabel, GuiUtil.setConstraints(0,0,0,0,GridBagConstraints.BOTH,0,0,0,5));
					block1_1.add(featureIdPrefix, GuiUtil.setConstraints(1,0,1.0,1.0,GridBagConstraints.BOTH,0,5,0,0));
				}
				
				block1.add(featureKeepExtRef, GuiUtil.setConstraints(0,3,1.0,1.0,GridBagConstraints.BOTH,0,lmargin,0,5));
				block1.add(featureAppendId, GuiUtil.setConstraints(0,4,1.0,1.0,GridBagConstraints.BOTH,0,lmargin,0,5));
			}
			
			add(block2, GuiUtil.setConstraints(0,1,1.0,0.0,GridBagConstraints.BOTH,5,0,5,0));
			block2.setBorder(BorderFactory.createTitledBorder(""));
			block2.setLayout(new GridBagLayout());
			lmargin = (int)(copyGeometry.getPreferredSize().getWidth()) + 11;
			xlinkToGeometry.setIconTextGap(10);
			copyGeometry.setIconTextGap(10);
			geometryAppendId.setIconTextGap(10);
			{
				block2.add(xlinkToGeometry, GuiUtil.setConstraints(0,0,1.0,1.0,GridBagConstraints.BOTH,0,5,0,5));
				block2.add(copyGeometry, GuiUtil.setConstraints(0,1,1.0,1.0,GridBagConstraints.BOTH,0,5,0,5));
				block2.add(block2_1, GuiUtil.setConstraints(0,2,1.0,1.0,GridBagConstraints.BOTH,0,lmargin,0,5));
				
				block2_1.setLayout(new GridBagLayout());
				block2_1.setBorder(BorderFactory.createEmptyBorder());
				{
					block2_1.add(geometryIdPrefixLabel, GuiUtil.setConstraints(0,0,0,0,GridBagConstraints.BOTH,0,0,0,5));
					block2_1.add(geometryIdPrefix, GuiUtil.setConstraints(1,0,1.0,0.0,GridBagConstraints.BOTH,0,5,0,0));
				}
				
				block2.add(geometryAppendId, GuiUtil.setConstraints(0,3,1.0,1.0,GridBagConstraints.BOTH,0,lmargin,0,5));
			}
		}
	}
	
	@Override
	public void doTranslation() {
		block1.setBorder(BorderFactory.createTitledBorder(ImpExpGui.labels.getString("pref.export.xlink.border.feature")));
		xlinkToFeature.setText(ImpExpGui.labels.getString("pref.export.xlink.label.feature.export"));
		copyFeature.setText(ImpExpGui.labels.getString("pref.export.xlink.label.feature.copy"));	
		featureIdPrefixLabel.setText(ImpExpGui.labels.getString("pref.export.xlink.label.copy.prefix"));
		featureAppendId.setText(ImpExpGui.labels.getString("pref.export.xlink.label.append"));
		featureKeepExtRef.setText(ImpExpGui.labels.getString("pref.export.xlink.label.feature.keepId"));

		block2.setBorder(BorderFactory.createTitledBorder(ImpExpGui.labels.getString("pref.export.xlink.border.geometry")));
		xlinkToGeometry.setText(ImpExpGui.labels.getString("pref.export.xlink.label.geometry.export"));
		copyGeometry.setText(ImpExpGui.labels.getString("pref.export.xlink.label.geometry.copy"));	
		geometryIdPrefixLabel.setText(ImpExpGui.labels.getString("pref.export.xlink.label.copy.prefix"));
		geometryAppendId.setText(ImpExpGui.labels.getString("pref.export.xlink.label.append"));
	}

	@Override
	public void loadSettings() {
		ExpXLinkFeatureConfig feature = config.getProject().getExporter().getXlink().getFeature();
		ExpXLinkConfig geometry = config.getProject().getExporter().getXlink().getGeometry();
		
		if (feature.getIdPrefix() != null && feature.getIdPrefix().trim().length() != 0)
			featureIdPrefix.setText(feature.getIdPrefix());
		else {
			featureIdPrefix.setText(UUIDManager.UUIDPrefix);
			feature.setIdPrefix(UUIDManager.UUIDPrefix);
		}
			
		if (feature.isModeXLink())
			xlinkToFeature.setSelected(true);
		else
			copyFeature.setSelected(true);	
		
		featureAppendId.setSelected(feature.isSetAppendId());
		featureKeepExtRef.setSelected(feature.isSetKeepGmlIdAsExternalReference());

		if (geometry.getIdPrefix() != null && geometry.getIdPrefix().trim().length() != 0)
			geometryIdPrefix.setText(geometry.getIdPrefix());
		else {
			geometryIdPrefix.setText(UUIDManager.UUIDPrefix);
			geometry.setIdPrefix(UUIDManager.UUIDPrefix);
		}
		
		if (geometry.isModeXLink())
			xlinkToGeometry.setSelected(true);
		else
			copyGeometry.setSelected(true);		
		
		geometryAppendId.setSelected(geometry.isSetAppendId());
	}

	@Override
	public void setSettings() {
		ExpXLinkFeatureConfig feature = config.getProject().getExporter().getXlink().getFeature();
		ExpXLinkConfig geometry = config.getProject().getExporter().getXlink().getGeometry();
		
		if (featureIdPrefix.getText() != null && featureIdPrefix.getText().trim().length() != 0)
			feature.setIdPrefix(featureIdPrefix.getText().trim());
		else {
			feature.setIdPrefix(UUIDManager.UUIDPrefix);
			featureIdPrefix.setText(UUIDManager.UUIDPrefix);
		}
		
		if (xlinkToFeature.isSelected())
			feature.setMode(ExpXLinkMode.XLINK);
		else
			feature.setMode(ExpXLinkMode.COPY);

		feature.setAppendId(featureAppendId.isSelected());
		feature.setKeepGmlIdAsExternalReference(featureKeepExtRef.isSelected());

		if (geometryIdPrefix.getText() != null && geometryIdPrefix.getText().trim().length() != 0)
			geometry.setIdPrefix(geometryIdPrefix.getText().trim());
		else {
			geometry.setIdPrefix(UUIDManager.UUIDPrefix);
			geometryIdPrefix.setText(UUIDManager.UUIDPrefix);
		}
		
		if (xlinkToGeometry.isSelected())
			geometry.setMode(ExpXLinkMode.XLINK);
		else
			geometry.setMode(ExpXLinkMode.COPY);
		
		geometry.setAppendId(geometryAppendId.isSelected());
	}

}
