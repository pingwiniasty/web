package com.owera.xaps.web.app.page.job;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.owera.common.db.NoAvailableConnectionException;
import com.owera.xaps.dbi.File;
import com.owera.xaps.dbi.FileType;
import com.owera.xaps.dbi.Group;
import com.owera.xaps.dbi.Job;
import com.owera.xaps.dbi.JobFlag;
import com.owera.xaps.dbi.JobFlag.JobServiceWindow;
import com.owera.xaps.dbi.JobFlag.JobType;
import com.owera.xaps.dbi.JobParameter;
import com.owera.xaps.dbi.Jobs;
import com.owera.xaps.dbi.Parameter;
import com.owera.xaps.dbi.UnitJobs;
import com.owera.xaps.dbi.Unittype;
import com.owera.xaps.dbi.UnittypeParameter;
import com.owera.xaps.dbi.XAPS;
import com.owera.xaps.web.Page;
import com.owera.xaps.web.app.Output;
import com.owera.xaps.web.app.input.DropDownSingleSelect;
import com.owera.xaps.web.app.input.InputDataIntegrity;
import com.owera.xaps.web.app.input.InputDataRetriever;
import com.owera.xaps.web.app.input.InputSelectionFactory;
import com.owera.xaps.web.app.input.ParameterParser;
import com.owera.xaps.web.app.menu.MenuItem;
import com.owera.xaps.web.app.page.AbstractWebPage;
import com.owera.xaps.web.app.page.file.FileComparator;
import com.owera.xaps.web.app.page.unittype.UnittypeParameterFlags;
import com.owera.xaps.web.app.page.unittype.UnittypeParameterTypes;
import com.owera.xaps.web.app.table.TableElement;
import com.owera.xaps.web.app.table.TableElementMaker;
import com.owera.xaps.web.app.util.SessionCache;
import com.owera.xaps.web.app.util.SessionData;
import com.owera.xaps.web.app.util.WebConstants;
import com.owera.xaps.web.app.util.XAPSLoader;

public class JobPage extends AbstractWebPage {
	// Do NOT static this variable, contains singletons that should NOT be shared by different views
	private final JobStatusMethods jobStatusMethods = new JobStatusMethods();

	// FIXME Why are we using class variables? What are the problem we are solving with this?
	private JobData inputData;

	private XAPS xaps;
	private Unittype unittype;

	private String sessionId;

	public void process(ParameterParser req, Output outputHandler) throws Exception {
		inputData = (JobData) InputDataRetriever.parseInto(new JobData(), req);

		sessionId = req.getSession().getId();

		xaps = XAPSLoader.getXAPS(sessionId);
		if (xaps == null) {
			outputHandler.setRedirectTarget(WebConstants.DB_LOGIN_URL);
			return;
		}

		if (inputData.getCmd().hasValue("create"))
			InputDataIntegrity.loadAndStoreSession(req, outputHandler, inputData, inputData.getUnittype());
		else
			InputDataIntegrity.loadAndStoreSession(req, outputHandler, inputData, inputData.getUnittype(), inputData.getJob());

		Map<String, Object> fmMap = outputHandler.getTemplateMap();
		DropDownSingleSelect<Unittype> unittypes = InputSelectionFactory.getUnittypeSelection(inputData.getUnittype(), xaps);
		fmMap.put("unittypes", unittypes);
		unittype = unittypes.getSelected();

		Job job = null;
		if (unittype != null)
			job = action(req, outputHandler, xaps);
		output(outputHandler, fmMap, job);
	}

	private void output(Output outputHandler, Map<String, Object> fmMap, Job jobFromAction) {
		if (jobFromAction != null) {
			outputHandler.getTrailPoint().setJobName(jobFromAction.getName()); // Make sure job is set in Context-menu 
			prepareEditOutput(jobFromAction, fmMap, xaps);
			outputHandler.setTemplatePath("job/details");
		} else if (inputData.getCmd().hasValue("create")) {
			prepareCreateOutput(jobFromAction, fmMap, xaps);
			outputHandler.getTrailPoint().setJobName(null);
			outputHandler.setTemplatePath("job/create");
		} else {
			boolean delete = inputData.getFormSubmit().hasValue(WebConstants.DELETE);
			Job jobFromSession = (unittype != null? unittype.getJobs().getByName(SessionCache.getSessionData(sessionId).getJobname()) : null);
			if (!delete && jobFromSession != null) {
				prepareEditOutput(jobFromSession, fmMap, xaps);
				outputHandler.setTemplatePath("job/details");
			} else {
				outputHandler.getTrailPoint().setJobName(null);
				outputHandler.setDirectToPage(Page.JOBSOVERVIEW);
			}
		}

	}

	public List<MenuItem> getShortcutItems(SessionData sessionData) {
		List<MenuItem> list = new ArrayList<MenuItem>();
		list.addAll(super.getShortcutItems(sessionData));
		if (unittype != null) {
			list.add(new MenuItem("Create new Job", Page.JOB).addCommand("create"));
			list.add(new MenuItem("Job overwiew", Page.JOBSOVERVIEW));
			list.add(new MenuItem("Group overview", Page.GROUPSOVERVIEW));
			if (sessionData.getJobname() != null) {
				Job job = unittype.getJobs().getByName(sessionData.getJobname());
				if (job != null) {
					list.add(new MenuItem("Job Group", Page.GROUP).addParameter("group", job.getGroup().getName()));
					list.add(new MenuItem("List failed unit jobs", Page.UNITJOB).addCommand("getfailedunitjobs").addParameter("limit", "100").addParameter("unittype", job.getUnittype().getName())
							.addParameter("group", job.getGroup().getName()).addParameter("job", job.getName()));
					list.add(new MenuItem("List completed unit jobs", Page.UNITJOB).addCommand("getcompletedunitjobs").addParameter("limit", "100").addParameter("unittype", job.getUnittype().getName())
							.addParameter("group", job.getGroup().getName()).addParameter("job", job.getName()));
				}
			}
		}
		return list;
	}

	private boolean actionCUDParameters(ParameterParser req, XAPS xaps, Job job) throws SQLException, NoAvailableConnectionException {
		Jobs xapsJobs = unittype.getJobs();
		UnittypeParameter[] utParams = unittype.getUnittypeParameters().getUnittypeParameters();
		List<JobParameter> deleteList = new ArrayList<JobParameter>();
		List<JobParameter> updateList = new ArrayList<JobParameter>();
		Map<String, JobParameter> jobParams = job.getDefaultParameters();
		for (UnittypeParameter utp : utParams) {
			if (req.getParameter("delete::" + utp.getName()) != null) {
				deleteList.add(jobParams.get(utp.getName()));
			} else if (req.getParameter("add::" + utp.getName()) != null) {
				String newValue = req.getParameter("update::" + utp.getName());
				if (newValue != null) {
					newValue = removeFromStart(newValue, '!');
				}
				if (newValue == null && !utp.getFlag().isReadOnly()) {
					newValue = "ENTER A VALUE";
				}
				JobParameter jp = new JobParameter(job, Job.ANY_UNIT_IN_GROUP, new Parameter(utp, newValue));
				updateList.add(jp);
			} else if (req.getParameter("update::" + utp.getName()) != null) {
				String updatedValue = req.getParameter("update::" + utp.getName()).trim();
				if (updatedValue != null) {
					updatedValue = removeFromStart(updatedValue, '!');
				}
				JobParameter jp = jobParams.get(utp.getName());
				if (jp != null && !updatedValue.equals(jp.getParameter().getValue())) {
					jp.getParameter().setValue(updatedValue);
					updateList.add(jp);
				}
			}
		}
		xapsJobs.deleteJobParameters(deleteList, xaps);
		xapsJobs.addOrChangeJobParameters(updateList, xaps);

		if (deleteList.size() > 0 || updateList.size() > 0) {
			return true;
		}

		return false;
	}

	// code-order: unty, id, name, flag, desc, group, unct, rules, file, dep, repc, repi

	private Job action(ParameterParser req, Output res, XAPS xaps) throws Exception {
		Jobs xapsJobs = unittype.getJobs();
		Job job = null;
		if (inputData.getFormSubmit().hasValue("Create new job") || inputData.getFormSubmit().hasValue(WebConstants.UPDATE)) {
			if (inputData.validateForm()) {
				if (inputData.getFormSubmit().hasValue("Create new job")) {
					job = new Job();
					job.setUnittype(unittype);
					job.setName(inputData.getName().getString());
				} else {
					job = unittype.getJobs().getByName(inputData.getName().getString());
				}
				JobType type = JobType.valueOf(inputData.getType().getString());
				JobServiceWindow serviceWindow = JobServiceWindow.valueOf(inputData.getServiceWindow().getString());
				job.setFlags(new JobFlag(type, serviceWindow));
				job.setDescription(inputData.getDescription().getString());
				job.setGroup(unittype.getGroups().getById(inputData.getGroupId().getInteger()));
				job.setUnconfirmedTimeout(inputData.getUnconfirmedTimeout().getInteger());
				job.setStopRules(inputData.getStoprules().getString());
				if (job.getFlags().getType().requireFile())
					job.setFile(unittype.getFiles().getById(inputData.getFileId().getInteger()));
				//				if (inputData.getDependency().notNullNorValue(WebConstants.ALL_ITEMS_OR_DEFAULT))
				job.setDependency(unittype.getJobs().getByName(inputData.getDependency().getString()));
				job.setRepeatCount(inputData.getRepeatCount().getInteger());
				job.setRepeatInterval(inputData.getRepeatInterval().getInteger());
				if (inputData.getFormSubmit().hasValue("Create new job")) {
					xapsJobs.add(job, xaps);
				} else
					xapsJobs.changeFromUI(job, xaps);
				return job;
			} else {
				res.getTemplateMap().put("errors", inputData.getErrors());
			}
		} else if (inputData.getFormSubmit().hasValue(WebConstants.DELETE)) {
			job = unittype.getJobs().getByName(SessionCache.getSessionData(sessionId).getJobname());
			UnitJobs unitJobs = new UnitJobs(SessionCache.getXAPSConnectionProperties(sessionId));
			unitJobs.delete(job);
			xapsJobs.deleteJobParameters(job, xaps);
			xapsJobs.delete(job, xaps);
			return null;
		} else if (inputData.getFormSubmit().hasValue(WebConstants.UPDATE_PARAMS)) {
			job = unittype.getJobs().getByName(SessionCache.getSessionData(sessionId).getJobname());
			actionCUDParameters(req, xaps, job);
			return job;
		} else if (inputData.getStatusSubmit().getString() != null) {
			job = unittype.getJobs().getByName(SessionCache.getSessionData(sessionId).getJobname());
			job.setStatus(jobStatusMethods.getJobStatusFromAcronym(inputData.getStatusSubmit().getString()));
			xapsJobs.changeStatus(job, xaps);
			return job;
		}
		return null;
	}

	private void prepareEditOutput(Job job, Map<String, Object> map, XAPS xaps) {
		map.put("job", job);
		map.put("haschildren", job.getChildren() != null && job.getChildren().size() > 0);
		map.put("requirefile", job.getFlags().getType().requireFile());
		map.put("hasjobdependecies", job.getDependency() == null && job.getChildren().size() == 0);
		map.put("dependencies", InputSelectionFactory.getDropDownSingleSelect(inputData.getDependency(), job.getDependency(), unittype.getJobs().getAllowedDependencies(job)));
		map.put("stoprules", job.getStopRulesSerialized());
		List<TableElement> params = new TableElementMaker().getParameters(new JobFilter(job), unittype.getUnittypeParameters().getUnittypeParameters(),
				job.getDefaultParameters().values().toArray(new JobParameter[] {}));
		map.put("params", params);
		map.put("filterflags", InputSelectionFactory.getDropDownSingleSelect(inputData.getFilterFlag(), inputData.getFilterFlag().getString("All"), UnittypeParameterFlags.toList()));
		String selectedType = inputData.getFilterType().getString("Configured");
		map.put("filtertypes", InputSelectionFactory.getDropDownSingleSelect(inputData.getFilterType(), selectedType, UnittypeParameterTypes.toList()));
		map.put("filterstring", inputData.getFilterString());
		map.put("acronym", jobStatusMethods.getStatusToAcronymMethod());
		map.put("original", jobStatusMethods.getStatusFromAcronymMethod());
		map.put("allowed", jobStatusMethods.getNextAvailableStatusCodesMethod());
		map.put("finished", jobStatusMethods.getIsStatusFinishedMethod());
		map.put("ready", jobStatusMethods.getIsStatusReadyMethod());
		File selectedFile = unittype.getFiles().getById(inputData.getFileId().getInteger());
		if (selectedFile == null)
			selectedFile = job.getFile();
		map.put("files", InputSelectionFactory.getDropDownSingleSelect(inputData.getFileId(), selectedFile, getFiles(job.getFlags().getType().getCorrelatedFileType())));
	}

	private void prepareCreateOutput(Job jobInAction, Map<String, Object> map, XAPS xaps) {
		if (unittype != null) {
			JobServiceWindow selectedJobWindow = JobServiceWindow.valueOf(getServiceWindowString());
			map.put("windows", InputSelectionFactory.getDropDownSingleSelect(inputData.getServiceWindow(), selectedJobWindow, Arrays.asList(JobServiceWindow.values())));
			map.put("groups", InputSelectionFactory.getGroupSelection(inputData.getGroupId(), unittype, xaps));
			Group selectedGroup = unittype.getGroups().getById(inputData.getGroupId().getInteger());
			map.put("groups", InputSelectionFactory.getDropDownSingleSelect(inputData.getGroupId(), selectedGroup, Arrays.asList(unittype.getGroups().getGroups())));
			JobType selectedJobType = JobType.valueOf(inputData.getType().getString());
			map.put("types", InputSelectionFactory.getDropDownSingleSelect(inputData.getType(), selectedJobType, Arrays.asList(JobType.values())));
			map.put("requirefile", selectedJobType.requireFile());
			Job selectedJobDependency = unittype.getJobs().getByName(inputData.getDependency().getString());
			map.put("dependencies", InputSelectionFactory.getDropDownSingleSelect(inputData.getDependency(), selectedJobDependency, unittype.getJobs().getAllowedDependencies(null)));
			map.put("name", inputData.getName().getString());
			map.put("description", inputData.getDescription().getString());
			map.put("unconfirmedtimeout", inputData.getUnconfirmedTimeout().getString());
			map.put("stoprules", inputData.getStoprules().getString());
			map.put("repeatcount", inputData.getRepeatCount().getString());
			map.put("repeatinterval", inputData.getRepeatInterval().getString());
			File selectedFile = unittype.getFiles().getById(inputData.getFileId().getInteger());
			map.put("files", InputSelectionFactory.getDropDownSingleSelect(inputData.getFileId(), selectedFile, getFiles(selectedJobType.getCorrelatedFileType())));
		}
	}

	private String getServiceWindowString() {
		if (inputData.getType().getString() == null)
			inputData.getType().setValue(JobType.CONFIG.toString());
		if (inputData.getServiceWindow().getString() == null)
			inputData.getServiceWindow().setValue(JobServiceWindow.REGULAR.toString());

		String currentJobType = SessionCache.getSessionData(sessionId).getJobType();
		boolean jobTypeChanged = false;
		if (currentJobType != null && !currentJobType.equals(inputData.getType().getString()))
			jobTypeChanged = true;

		// !! Here this method does more to that state of the web page, than simply returning a string !!
		// However, this change of state cannot be performed until the previous comparison between
		// current and new jobType.
		SessionCache.getSessionData(sessionId).setJobType(inputData.getType().getString());

		JobServiceWindow jobWindow = JobServiceWindow.valueOf(inputData.getServiceWindow().getString());
		JobType jobType = JobType.valueOf(inputData.getType().getString());
		boolean regularSelected = false;
		if (!jobTypeChanged && jobWindow == JobServiceWindow.REGULAR)
			regularSelected = true;
		else if (jobTypeChanged && (jobType == JobType.CONFIG || jobType == JobType.TR069_SCRIPT))
			regularSelected = true;

		boolean disruptedSelected = false;
		if (!jobTypeChanged && jobWindow == JobServiceWindow.DISRUPTIVE) {
			disruptedSelected = true;
		} else if (jobTypeChanged && (jobType == JobType.SOFTWARE || jobType == JobType.RESET || jobType == JobType.RESTART)) {
			disruptedSelected = true;
		}
		return (disruptedSelected ? JobServiceWindow.DISRUPTIVE : (regularSelected ? JobServiceWindow.REGULAR : JobServiceWindow.DISRUPTIVE)).toString();
	}

	private List<File> getFiles(FileType requiredType) {
		List<File> list = new ArrayList<File>();
		if (unittype != null) {
			list = Arrays.asList(unittype.getFiles().getFiles(requiredType));
			Collections.sort(list, new FileComparator(FileComparator.DATE));
		}
		return list;
	}
}
