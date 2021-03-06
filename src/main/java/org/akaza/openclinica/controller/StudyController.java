package org.akaza.openclinica.controller;

import java.io.IOException;
import java.io.StringWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.StringReader;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

import core.org.akaza.openclinica.domain.Status;
import freemarker.template.TemplateException;
import io.swagger.annotations.Api;
import core.org.akaza.openclinica.bean.core.NumericComparisonOperator;
import core.org.akaza.openclinica.bean.core.Role;
import core.org.akaza.openclinica.bean.core.UserType;
import core.org.akaza.openclinica.bean.login.EventDefinitionDTO;
import core.org.akaza.openclinica.bean.login.FacilityInfo;
import core.org.akaza.openclinica.bean.login.ResponseSuccessEventDefDTO;
import core.org.akaza.openclinica.bean.login.ResponseSuccessSiteDTO;
import core.org.akaza.openclinica.bean.login.ResponseSuccessStudyDTO;
import core.org.akaza.openclinica.bean.login.SiteDTO;
import core.org.akaza.openclinica.bean.login.StudyDTO;
import core.org.akaza.openclinica.bean.login.StudyUserRoleBean;
import core.org.akaza.openclinica.bean.login.UserAccountBean;
import core.org.akaza.openclinica.bean.login.UserRole;
import core.org.akaza.openclinica.bean.managestudy.StudyEventDefinitionBean;
import org.akaza.openclinica.config.StudyParamNames;
import org.akaza.openclinica.control.form.Validator;
import org.akaza.openclinica.controller.dto.ParticipantIdModel;
import org.akaza.openclinica.controller.dto.ParticipantIdVariable;
import org.akaza.openclinica.controller.dto.SiteStatusDTO;
import org.akaza.openclinica.controller.dto.StudyEnvStatusDTO;
import org.akaza.openclinica.controller.helper.AsyncStudyHelper;
import core.org.akaza.openclinica.service.OCUserDTO;
import core.org.akaza.openclinica.service.StudyEnvironmentRoleDTO;
import org.akaza.openclinica.controller.helper.StudyInfoObject;
import core.org.akaza.openclinica.dao.core.CoreResources;
import core.org.akaza.openclinica.dao.hibernate.StudyDao;
import core.org.akaza.openclinica.dao.hibernate.StudyParameterDao;
import core.org.akaza.openclinica.dao.hibernate.StudyUserRoleDao;
import core.org.akaza.openclinica.dao.login.UserAccountDAO;
import core.org.akaza.openclinica.dao.managestudy.StudyEventDefinitionDAO;
import core.org.akaza.openclinica.domain.datamap.Study;
import core.org.akaza.openclinica.domain.datamap.StudyEnvEnum;
import core.org.akaza.openclinica.domain.datamap.StudyParameter;
import core.org.akaza.openclinica.domain.datamap.StudyParameterValue;
import core.org.akaza.openclinica.i18n.core.LocaleResolver;
import core.org.akaza.openclinica.i18n.util.ResourceBundleProvider;
import core.org.akaza.openclinica.service.LiquibaseOnDemandService;
import core.org.akaza.openclinica.service.SchemaCleanupService;
import core.org.akaza.openclinica.service.SiteBuildService;
import core.org.akaza.openclinica.service.StudyBuildService;
import core.org.akaza.openclinica.service.crfdata.ErrorObj;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import freemarker.template.Configuration;
import freemarker.template.Template;


@Controller
@Api(value = "Study", tags = { "Study" }, description = "REST API for Study")
@RequestMapping(value = "/auth/api/v1/studies")
public class StudyController {
    public static ResourceBundle resadmin, resaudit, resexception, resformat, respage, resterm, restext, resword, resworkflow;
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    @Autowired
    UserAccountController userAccountController;
    UserAccountDAO udao;
    StudyEventDefinitionDAO seddao;
    @Autowired
    @Qualifier("dataSource")
    private DataSource dataSource;
    @Autowired
    private StudyDao studyDao;
    @Autowired
    private StudyUserRoleDao studyUserRoleDao;
    @Autowired
    private StudyBuildService studyBuildService;
    @Autowired
    private LiquibaseOnDemandService liquibaseOnDemandService;
    @Autowired
    private SiteBuildService siteBuildService;
    @Autowired
    private SchemaCleanupService schemaCleanupService;
    @Autowired
    StudyParameterDao studyParameterDao;
    @Autowired
    private Configuration freemarkerConfiguration;

    public StudyController(StudyDao studyDao) {
        this.studyDao = studyDao;
    }
    private final String RANDOM="random number";
    private final String HELPER_RANDOM="helper.random";

    private final String SITE_PARTICIPANT_COUNT="siteParticipantCount";
    private final String SITE_ID="siteId";


    private enum SiteSaveCheck {
        CHECK_UNIQUE_SAVE(0), CHECK_UNIQUE_UPDATE(1), NO_CHECK(2);
        private int code;
        SiteSaveCheck(int code) {
            this.code = code;
        }
    }

    private static final String validation_failed_message = "VALIDATION FAILED";
    private static final String validation_passed_message = "SUCCESS";

    /**
     * @api {post} /pages/auth/api/v1/studies/ Create a study
     * @apiName createNewStudy
     * @apiPermission Authenticate using api-key. admin
     * @apiVersion 3.8.0
     * @apiParam {String} uniqueProtococlId Study unique study ID.
     * @apiParam {String} briefTitle Brief Title .
     * @apiParam {String} principalInvestigator Principal Investigator Name.
     * @apiParam {Integer} expectedTotalEnrollment Expected Total Enrollment number
     * @apiParam {String} sponsor Sponsor name.
     * @apiParam {String} studyType 'Interventional' or ' Observational'
     * @apiParam {String} status 'Available' or 'Design'
     * @apiParam {String} briefSummary Study Summary
     * @apiParam {Date} startDate Start date
     * @apiParam {Array} assignUserRoles Assign Users to Roles for this Study.
     * @apiGroup Study
     * @apiHeader {String} api_key Users unique access-key.
     * @apiDescription This API is to create a New Study in OC.
     * All the fields are required fields and can't be left blank.
     * You need to provide your Api-key to be connected.
     * @apiParamExample {json} Request-Example:
     * {
     * "briefTitle": "Study Study ID Name",
     * "principalInvestigator": "Principal Investigator Name",
     * "expectedTotalEnrollment": "10",
     * "sponsor": "Sponsor Name",
     * "studyType": "Interventional",
     * "status": "available",
     * "assignUserRoles": [
     * { "username": "usera", "role": "Data Manager" },
     * { "username": "userb", "role": "Study Director" },
     * { "username": "userc", "role": "Data Specialist" },
     * { "username": "userd", "role": "Monitor" },
     * { "username": "usere", "role": "Data Entry Person" }
     * ],
     * "uniqueStudyID": "Study Study ID",
     * "briefSummary": "Study Summary",
     * "startDate": "2011-11-11"
     * }
     * @apiErrorExample {json} Error-Response:
     * HTTP/1.1 400 Bad Request
     * {
     * "message": "VALIDATION FAILED",
     * "status": "available",
     * "principalInvestigator": "Principal Investigator Name",
     * "expectedTotalEnrollment": "10",
     * "sponsor": "Sponsor Name",
     * "studyType": "Interventional",
     * "errors": [
     * {"field": "UniqueStudyId","resource": "Study Object","code": "Unique Study Id exist in the System"}
     * ],
     * "startDate": "2011-11-11",
     * "assignUserRoles": [
     * {"username": "usera","role": "Data Manager"},
     * {"username": "userb","role": "Study Director"},
     * {"username": "userc","role": "Data Specialist"}
     * ],
     * "uniqueStudyID": "Study Study ID",
     * "briefTitle": "Study Study ID",
     * "briefSummary": "Study Summary",
     * "studyOid": null
     * }
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {
     * "message": "SUCCESS",
     * "uniqueStudyID": "Study Study ID",
     * "studyOid": "S_STUDYPRO",
     * }
     */

    @RequestMapping(value = "/{studyEnvUuid}/status", method = RequestMethod.PUT)
    public ResponseEntity<Object> changeStudyStatus(
            @RequestBody HashMap<String, Object> requestDTO,
            @PathVariable("studyEnvUuid") String studyEnvUuid,
            HttpServletRequest request) {

        ResponseEntity response = null;
        ArrayList<ErrorObj> errorObjects = new ArrayList<ErrorObj>();

        // Set the locale, status object needs this
        Locale locale = new Locale("en_US");
        request.getSession().setAttribute(LocaleResolver.getLocaleSessionAttributeName(), locale);
        ResourceBundleProvider.updateLocale(locale);

        UserAccountBean ub = (UserAccountBean) request.getSession().getAttribute("userBean");

        if (ub == null) {
            logger.error("No userBean found in the session.");
            return new ResponseEntity<Object>("Not permitted.", HttpStatus.FORBIDDEN);
        }
        // Get public study
        Study currentPublicStudy = studyDao.findByStudyEnvUuid(studyEnvUuid);
        // Get tenant study
        String tenantSchema = currentPublicStudy.getSchemaName();
        CoreResources.setRequestSchema(request, tenantSchema);
        Study currentStudy = studyDao.findByStudyEnvUuid(studyEnvUuid);
        // Validate study exists
        if (currentPublicStudy == null || currentStudy == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing or invalid", "studyEnvUuid");
            errorObjects.add(errorObject);
            return new ResponseEntity(errorObjects, org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        if (ub != null){
            CoreResources.setRequestSchema(request, "public");
            // update the user roles since they may have changed
            studyBuildService.updateStudyUserRoles(request, studyBuildService.getUserAccountObject(ub), ub.getActiveStudyId(), null, false);
            // get the new bean
            UserAccountDAO userAccountDAO = new UserAccountDAO(dataSource);
            if (StringUtils.isEmpty(ub.getUserUuid()))
                ub = (UserAccountBean) userAccountDAO.findByUserName(ub.getName());
            else
                ub = (UserAccountBean) userAccountDAO.findByUserUuid(ub.getUserUuid());
            if (!roleValidForStatusChange(ub,currentPublicStudy, 1)){
                logger.error("User does not have a proper role to do this operation");
                return new ResponseEntity<Object>("Not permitted.", HttpStatus.FORBIDDEN);
            }
        }
        CoreResources.setRequestSchema(request, tenantSchema);
        // Get Status object from requestDTO
        String statusValue =  (String) requestDTO.get("status");
        if (statusValue != null)
            statusValue = statusValue.equals("DESIGN") ? "PENDING" : statusValue;
        Status status = getStatus(statusValue);
        // Validate status field
        if (status == null ) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "status");
            errorObjects.add(errorObject);
            return new ResponseEntity(errorObjects, org.springframework.http.HttpStatus.BAD_REQUEST);
        } else if (!status.equals(Status.PENDING)
                && !status.equals(Status.AVAILABLE)
                && !status.equals(Status.FROZEN)
                && !status.equals(Status.LOCKED) ){
            ErrorObj errorObject = createErrorObject("Study Object", "Invalid status", "status");
            errorObjects.add(errorObject);
            return new ResponseEntity(errorObjects, org.springframework.http.HttpStatus.BAD_REQUEST);
        }


        // Update tenant study & sites
        currentStudy.setOldStatusId(currentStudy.getStatus().getCode());
        currentStudy.setStatus(status);
        studyDao.updateStudyStatus(currentStudy);
        ArrayList siteList = (ArrayList) studyDao.findAllByParent(currentStudy.getStudyId());
        if (siteList.size() > 0) {
            studyDao.updateSitesStatus(currentStudy);
        }

        // Update public study & sites
        CoreResources.setRequestSchema(request, "public");
        currentPublicStudy.setOldStatusId(currentPublicStudy.getStatus().getCode());
        currentPublicStudy.setStatus(status);
        studyDao.updateStudyStatus(currentPublicStudy);
        ArrayList publicSiteList = (ArrayList) studyDao.findAllByParent(currentPublicStudy.getStudyId());
        if (publicSiteList.size() > 0) {
            studyDao.updateSitesStatus(currentPublicStudy);
        }

        StudyEnvStatusDTO studyEnvStatusDTO = new StudyEnvStatusDTO();
        studyEnvStatusDTO.setStudyEnvUuid(currentPublicStudy.getStudyEnvUuid());
        studyEnvStatusDTO.setStatus(currentPublicStudy.getStatus().getName());
        ArrayList updatedPublicSiteList = (ArrayList) studyDao.findAllByParent(currentPublicStudy.getStudyId());
        for(Study site:  (ArrayList<Study>)updatedPublicSiteList){
            SiteStatusDTO siteStatusDTO = new SiteStatusDTO();
            siteStatusDTO.setSiteUuid(site.getStudyEnvSiteUuid());
            siteStatusDTO.setStatus(site.getStatus().getName());
            studyEnvStatusDTO.getSiteStatuses().add(siteStatusDTO);
        }

        return  new ResponseEntity(studyEnvStatusDTO, org.springframework.http.HttpStatus.OK);
    }

    private List<StudyParameterValue> processStudyParameterValues(HashMap<String, Object> map, ArrayList<ErrorObj> errorObjects , String templateID) {
        List<StudyParameterValue> studyParameterValues = new ArrayList<>();
        String collectBirthDate = (String) map.get("collectDateOfBirth");
        Boolean collectSex = (Boolean) map.get("collectSex");
        String collectPersonId = (String) map.get("collectPersonId");
        Boolean showSecondaryId = (Boolean) map.get("showSecondaryId");
        String enforceEnrollmentCap =  String.valueOf(map.get("enforceEnrollmentCap"));
        if (templateID != null && !StringUtils.isEmpty(templateID)) {
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.SUBJECT_ID_GENERATION, "auto non-editable"));
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.PARTICIPANT_ID_TEMPLATE, templateID));
        }
        else {
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.SUBJECT_ID_GENERATION, "manual"));
        }
        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.DISCREPANCY_MANAGEMENT, "true"));

        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.INTERVIEWER_NAME_REQUIRED, "not_used"));

        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.INTERVIEWER_NAME_EDITABLE, "true"));
        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.INTERVIEW_DATE_REQUIRED, "not_used"));
        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.INTERVIEW_DATE_DEFAULT, "blank"));
        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.INTERVIEW_DATE_EDITABLE, "true"));
        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.SUBJECT_ID_PREFIX_SUFFIX, "true"));
        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.PERSON_ID_SHOWN_ON_CRF, "false"));


        if (collectBirthDate == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "CollectBirthDate");
            errorObjects.add(errorObject);
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.COLLECT_DOB, "3"));
        } else {
            collectBirthDate = collectBirthDate.trim();
        }
        if (StringUtils.isEmpty(collectBirthDate)) {
            collectBirthDate ="3";
        } else {
            switch (collectBirthDate.toLowerCase()) {
                case "always":
                    collectBirthDate  = "1";
                    break;
                case "only_the_year":
                    collectBirthDate = "2";
                    break;
                default:
                    collectBirthDate = "3";
                    break;
            }
        }
        studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.COLLECT_DOB, collectBirthDate));

        if (collectSex == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "CollectSex");
            errorObjects.add(errorObject);
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.GENDER_REQUIRED, "true"));
        }
        else
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.GENDER_REQUIRED, Boolean.toString(collectSex)));
        if (collectPersonId == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "CollectPersonId");
            errorObjects.add(errorObject);
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.SUBJECT_PERSON_ID_REQUIRED, "required"));
        } else {
            collectPersonId = collectPersonId.trim();
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.SUBJECT_PERSON_ID_REQUIRED, collectPersonId));
        }

        if (showSecondaryId == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "ShowSecondaryId");
            errorObjects.add(errorObject);
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.SECONDARY_LABEL_VIEWABLE, "false"));
        }
        else
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.SECONDARY_LABEL_VIEWABLE, Boolean.toString(showSecondaryId)));
        if (enforceEnrollmentCap == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "EnforceEnrollmentCap");
            errorObjects.add(errorObject);
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.ENFORCE_ENROLLMENT_CAP, "false"));
        }
        else
            studyParameterValues.add(createStudyParameterValueWithHandleAndValue(StudyParamNames.ENFORCE_ENROLLMENT_CAP, enforceEnrollmentCap));

        return studyParameterValues;
    }
    public StudyParameterValue createStudyParameterValueWithHandleAndValue(String handle, String parameterValue){
        StudyParameterValue spv = new StudyParameterValue();
        StudyParameter parameter = studyParameterDao.findByHandle(handle);
        spv.setStudyParameter(parameter);
        spv.setValue(parameterValue);
        return  spv;
    }

    @RequestMapping(value = "/", method = RequestMethod.PUT)
    public ResponseEntity<Object> UpdateStudy(HttpServletRequest request,
                                              @RequestBody HashMap<String, Object> map) throws Exception {
        ArrayList<ErrorObj> errorObjects = new ArrayList();
        logger.info("In Update Study Settings");
        ResponseEntity<Object> response = null;

        StudyParameters parameters = new StudyParameters(map);
        parameters.setParameters();
        errorObjects = parameters.validateParameters(request);

        // get the study to update
        CoreResources.setRequestSchema(request, "public");
        Study existingStudy = studyDao.findByStudyEnvUuid(parameters.studyEnvUuid);
        if (existingStudy == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "Missing Study", "studyEnvUuid");
            errorObjects.add(errorObject);
        }

        if (errorObjects != null && errorObjects.size() != 0) {

            response = new ResponseEntity(errorObjects, org.springframework.http.HttpStatus.BAD_REQUEST);
            return response;
        }
        setChangeableStudySettings(existingStudy, parameters);
        studyDao.saveOrUpdate(existingStudy);
        String schema = existingStudy.getSchemaName();
        CoreResources.setRequestSchema(request, schema);
        Study schemaStudy = studyDao.findByStudyEnvUuid(existingStudy.getStudyEnvUuid());
        setChangeableStudySettings(schemaStudy, parameters);
        updateStudyConfigParameters(request, schemaStudy, parameters.studyParameterValues, parameters.templateID , parameters.enrollmentCap);

        ResponseSuccessStudyDTO responseSuccess = new ResponseSuccessStudyDTO();
        responseSuccess.setMessage(validation_passed_message);
        responseSuccess.setStudyOid(schemaStudy.getOc_oid());
        responseSuccess.setUniqueStudyID(schemaStudy.getUniqueIdentifier());
        responseSuccess.setSchemaName(schema);
        response = new ResponseEntity(responseSuccess, org.springframework.http.HttpStatus.OK);
        return response;
    }


    private class StudyParameters {
        HashMap<String, Object> map;
        String uniqueStudyID;
        String name;
        String studyOid;
        String studyEnvUuid;
        String description;
        String studyType;
        String phase;
        String startDateStr;
        String endDateStr;
        Integer expectedTotalEnrollment;
        Date startDate;
        Date endDate;
        List<StudyParameterValue> studyParameterValues;
        core.org.akaza.openclinica.domain.Status status;
        String templateID;
        Boolean enrollmentCap;
        String studyUuid;


        public StudyParameters(HashMap<String, Object> map) {
            this.map = map;
        }

        void setParameters() {
            uniqueStudyID = (String) map.get("uniqueStudyID");
            name = (String) map.get("briefTitle");
            studyOid = (String) map.get("studyEnvOid");
            studyEnvUuid = (String) map.get("studyEnvUuid");
            description = (String) map.get("description");
            studyType = (String) map.get("type");
            phase = (String) map.get("phase");
            startDateStr = (String) map.get("expectedStartDate");
            endDateStr = (String) map.get("expectedEndDate");
            expectedTotalEnrollment = (Integer) map.get("expectedTotalEnrollment");
            status = setStatus((String) map.get("status"));
            templateID = (String) map.get("participantIdTemplate");
            enrollmentCap = (Boolean) map.get("enforceEnrollmentCap");
            studyUuid = (String) map.get("uuid");
        }

        core.org.akaza.openclinica.domain.Status setStatus(String myStatus) {

            // set status object if no status pass default it to "PENDING"
            core.org.akaza.openclinica.domain.Status statusObj = core.org.akaza.openclinica.domain.Status.PENDING;

            if (myStatus != null) {
                myStatus = myStatus.equals("DESIGN") ? "PENDING" : myStatus;
                statusObj = core.org.akaza.openclinica.domain.Status.getByName(myStatus.toLowerCase());
            }
            return statusObj;
        }

        ArrayList<ErrorObj> validateParameters(HttpServletRequest request) throws ParseException {
            ArrayList<ErrorObj> errorObjects = new ArrayList();

            if (StringUtils.isEmpty(uniqueStudyID)) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "UniqueStudyID");
                errorObjects.add(errorObject);
            } else {
                uniqueStudyID = uniqueStudyID.trim();
            }
            if (StringUtils.isEmpty(name)) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "BriefTitle");
                errorObjects.add(errorObject);
            } else {
                name = name.trim();
            }
            if (description == null) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "Description");
                errorObjects.add(errorObject);
            } else {
                description = description.trim();
            }

            if (expectedTotalEnrollment == null) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "ExpectedTotalEnrollment");
                errorObjects.add(errorObject);
            }

            if (startDateStr == null) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "StartDate");
                errorObjects.add(errorObject);
            } else {
                startDateStr = startDateStr.trim();
            }
            startDate = formatDateString(startDateStr, "StartDate", errorObjects);

            if (endDateStr == null) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "EndDate");
                errorObjects.add(errorObject);
            } else {
                endDateStr = endDateStr.trim();
            }
            endDate = formatDateString(endDateStr, "EndDate", errorObjects);

            if (studyType != null) {
                studyType = studyType.toLowerCase();
                if (!verifyStudyTypeExist(studyType)) {
                    ErrorObj errorObject = createErrorObject("Study Object", "Study Type is not Valid", "StudyType");
                    errorObjects.add(errorObject);
                }
            }

            if (StringUtils.isEmpty(studyOid)) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "oid");
                errorObjects.add(errorObject);
            } else {
                studyOid = studyOid.trim();
            }

            if (StringUtils.isEmpty(studyEnvUuid)) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "studyEnvUuid");
                errorObjects.add(errorObject);
            } else {
                studyEnvUuid = studyEnvUuid.trim();
            }

            if (StringUtils.isEmpty(studyUuid)) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "studyUuid");
                errorObjects.add(errorObject);
            } else {
                studyUuid = studyUuid.trim();
            }

            if (status == null ) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "status");
                errorObjects.add(errorObject);
            } else if (!status.equals(core.org.akaza.openclinica.domain.Status.PENDING)
                    && !status.equals(core.org.akaza.openclinica.domain.Status.AVAILABLE)
                    && !status.equals(core.org.akaza.openclinica.domain.Status.FROZEN)
                    && !status.equals(core.org.akaza.openclinica.domain.Status.LOCKED) ){
                ErrorObj errorObject = createErrorObject("Study Object", "Invalid status", "status");
                errorObjects.add(errorObject);
            }

            studyParameterValues = processStudyParameterValues(map, errorObjects, templateID);
            Locale locale = new Locale("en_US");
            request.getSession().setAttribute(LocaleResolver.getLocaleSessionAttributeName(), locale);
            ResourceBundleProvider.updateLocale(locale);

            request.setAttribute("uniqueStudyID", uniqueStudyID);
            request.setAttribute("name", name); // Brief Title
            request.setAttribute("oid", studyOid);
            request.setAttribute("studyEnvUuid", studyEnvUuid);
            request.setAttribute("studyUuid", studyUuid);

            Validator v0 = new Validator(request);
            v0.addValidation("name", Validator.NO_BLANKS);

            if (templateID != null) {     // templateID
                logger.info("TemplateID: "+ templateID);
                verifyTemplateID(templateID,errorObjects);
            }

            HashMap vError0 = v0.validate();
            if (!vError0.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Study Object", "This field cannot be blank.", "BriefTitle");
                errorObjects.add(errorObject);
            }

            Validator v1 = new Validator(request);
            v1.addValidation("uniqueStudyID", Validator.NO_BLANKS);
            HashMap vError1 = v1.validate();
            if (!vError1.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Study Object", "This field cannot be blank.", "UniqueStudyId");
                errorObjects.add(errorObject);
            }

            Validator v2 = new Validator(request);
            v2.addValidation("oid", Validator.NO_BLANKS);
            HashMap vError2 = v2.validate();
            if (!vError2.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Study Object", "This field cannot be blank.", "oid");
                errorObjects.add(errorObject);
            }

            return errorObjects;
        }
    }

    @RequestMapping(value = "/", method = RequestMethod.POST)
    public ResponseEntity<Object> createNewStudy(HttpServletRequest request,
                                                 @RequestBody HashMap<String, Object> map) throws Exception {
        StudyDTO studyDTO = new StudyDTO();
        logger.info("In Create Study");
        ResponseEntity<Object> response = null;

        StudyParameters parameters = new StudyParameters(map);
        parameters.setParameters();
        ArrayList<ErrorObj> errorObjects = parameters.validateParameters(request);
        Matcher m = Pattern.compile("(.+)\\((.+)\\)").matcher(parameters.studyOid);
        String envType = "";
        if (m.find()) {
            if (m.groupCount() != 2) {
                ErrorObj errorObject = createErrorObject("Study Object", "Missing Field", "envType");
                errorObjects.add(errorObject);
            } else {
                envType = m.group(2).toUpperCase();
            }
        }

        AsyncStudyHelper asyncStudyHelper = new AsyncStudyHelper("Study Creation Started", "PENDING", LocalTime.now());
        AsyncStudyHelper.put(parameters.uniqueStudyID, asyncStudyHelper);

        ResponseEntity<Map<String, Object>> responseEntity = processSSOUserContext(request, parameters.studyEnvUuid);

        UserAccountBean ownerUserAccount = getStudyOwnerAccountWithCreatedUser(request, responseEntity);
        if (ownerUserAccount == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "The Owner User Account is not Valid Account or Does not have Admin user type",
                    "Owner Account");
            errorObjects.add(errorObject);

        }

        Validator v4 = new Validator(request);
        v4.addValidation("role", Validator.NO_LEADING_OR_TRAILING_SPACES);
        HashMap vError4 = v4.validate();
        if (!vError4.isEmpty()) {
            ErrorObj errorObject = createErrorObject("Study Object", "This field cannot have leading or trailing spaces.", "role");
            errorObjects.add(errorObject);
        }

        if (errorObjects != null && errorObjects.size() != 0) {

            response = new ResponseEntity(errorObjects, org.springframework.http.HttpStatus.BAD_REQUEST);
            return response;
        }

        Study study = new Study();
        setChangeableStudySettings(study, parameters);
        study.setEnvType(StudyEnvEnum.valueOf(envType));
        Study byOidEnvType = studyDao.findByOidEnvType(parameters.studyOid, StudyEnvEnum.valueOf(envType));
        if (byOidEnvType != null && byOidEnvType.getOc_oid() != null) {
            return getResponseSuccess(byOidEnvType);
        }
        Study schemaStudy = createSchemaStudy(request, study, ownerUserAccount);
        setStudyParameterValuesToTheStudy(request, study, schemaStudy, parameters.studyParameterValues , parameters.templateID);
        logger.debug("returning from liquibase study:" + schemaStudy.getStudyId());

        if (errorObjects != null && errorObjects.size() != 0) {
            response = new ResponseEntity(errorObjects, org.springframework.http.HttpStatus.BAD_REQUEST);
        } else {
            studyDTO.setStudyOid(schemaStudy.getOc_oid());
            studyDTO.setUniqueProtocolID(schemaStudy.getUniqueIdentifier());
            logger.debug("study oc_id:" + schemaStudy.getOc_oid());

            ResponseSuccessStudyDTO responseSuccess = new ResponseSuccessStudyDTO();
            responseSuccess.setMessage(studyDTO.getMessage());
            responseSuccess.setStudyOid(studyDTO.getStudyOid());
            responseSuccess.setUniqueStudyID(studyDTO.getUniqueProtocolID());
            responseSuccess.setSchemaName(study.getSchemaName());
            response = new ResponseEntity(responseSuccess, org.springframework.http.HttpStatus.OK);
        }
        request.getSession().setAttribute("userContextMap", null);
        AsyncStudyHelper asyncStudyDone = new AsyncStudyHelper("Finished creating study", "ACTIVE");
        AsyncStudyHelper.put(parameters.uniqueStudyID, asyncStudyDone);

        return response;

    }

    private void setChangeableStudySettings(Study study, StudyParameters parameters) {
        study.setUniqueIdentifier(parameters.uniqueStudyID);
        study.setName(parameters.name);
        study.setOc_oid(parameters.studyOid);
        study.setStudyEnvUuid(parameters.studyEnvUuid);
        study.setPhase(parameters.phase);
        study.setDatePlannedStart(parameters.startDate);
        study.setDatePlannedEnd(parameters.endDate);
        study.setExpectedTotalEnrollment(parameters.expectedTotalEnrollment);
        study.setProtocolType(parameters.studyType.toLowerCase());
        study.setProtocolDescription(parameters.description);
        if(study.getStatus() != null){
            study.setOldStatusId(study.getStatus().getCode());
        }
        study.setStatus(parameters.status);
        study.setStudyUuid(parameters.studyUuid);
    }

    private Study createSchemaStudy(HttpServletRequest request, Study study, UserAccountBean ownerUserAccount) throws Exception {
        StudyInfoObject studyInfoObject = null;
        Study schemaStudy = null;
        try {

            studyInfoObject = studyBuildService.process(request, study, ownerUserAccount);
            liquibaseOnDemandService.createForeignTables(studyInfoObject);
            schemaStudy = liquibaseOnDemandService.process(studyInfoObject, studyInfoObject.getUb());
        } catch (Exception e) {
            try {
                schemaCleanupService.dropSchema(studyInfoObject);
            } catch (Exception schemaEx) {
                throw new Exception("Schema cleanup failed.");
            }
            throw e;
        }
        return schemaStudy;
    }

    private void updateStudyConfigParameters(HttpServletRequest request, Study schemaStudy, List<StudyParameterValue> newStudyParameterValues, String templateID ,Boolean enrollmentCap) {

        addParameterValue( templateID, schemaStudy, StudyParamNames.PARTICIPANT_ID_TEMPLATE);
        addParameterValue( enrollmentCap.toString(), schemaStudy, StudyParamNames.ENFORCE_ENROLLMENT_CAP);

        for(StudyParameterValue spv : newStudyParameterValues){
            if(spv.getStudyParameter().getHandle().equalsIgnoreCase(StudyParamNames.SUBJECT_ID_GENERATION)) {
                if (templateID != null && !StringUtils.isEmpty(templateID))
                    addParameterValue("auto non-editable", schemaStudy, StudyParamNames.SUBJECT_ID_GENERATION);
                else
                    addParameterValue( spv.getValue(), schemaStudy, spv.getStudyParameter().getHandle());
            }
            else if(spv.getStudyParameter().getHandle().equalsIgnoreCase(StudyParamNames.PARTICIPANT_ID_TEMPLATE) || spv.getStudyParameter().getHandle().equalsIgnoreCase(StudyParamNames.ENFORCE_ENROLLMENT_CAP))
                continue;
            else
                    addParameterValue( spv.getValue(), schemaStudy, spv.getStudyParameter().getHandle());
        }
        studyDao.saveOrUpdate(schemaStudy);
    }

    private String handlePersonIdRequired(String input) {
        String outputStr = "";
        switch (input.toLowerCase()) {
            case "always":
                outputStr = "required";
                break;
            case "optional":
                outputStr = "optional";
                break;
            case "never":
                outputStr = "never";
                break;
            default:
                break;
        }
        return outputStr;
    }

    private void setStudyParameterValuesToTheStudy(HttpServletRequest request, Study study, Study schemaStudy, List<StudyParameterValue> studyParameterValues, String templateID) {
        String schema = CoreResources.getRequestSchema(request);
        CoreResources.setRequestSchema(request, study.getSchemaName());
//        List<StudyParameterValue> studyParameterValues = new ArrayList<>();
        for(StudyParameterValue spv: studyParameterValues){
            spv.setStudy(schemaStudy);
        }
        schemaStudy.setStudyParameterValues(studyParameterValues);

        studyDao.saveOrUpdate(schemaStudy);
        if (StringUtils.isNotEmpty(schema))
            CoreResources.setRequestSchema(request, schema);
    }

    private Date formatDateString(String dateStr, String fieldName, List<ErrorObj> errorObjects) throws ParseException {
        String format = "yyyy-MM-dd";
        SimpleDateFormat formatter = null;
        Date formattedDate = null;
        if (dateStr != "" && dateStr != null) {
            try {
                formatter = new SimpleDateFormat(format);
                formattedDate = formatter.parse(dateStr);
            } catch (ParseException e) {
                ErrorObj errorObject = createErrorObject("Study Object",
                        "The StartDate format is not a valid 'yyyy-MM-dd' format", "fieldName");
                errorObjects.add(errorObject);
            }
            if (formattedDate != null) {
                if (!dateStr.equals(formatter.format(formattedDate))) {
                    ErrorObj errorObject = createErrorObject("Study Object",
                            "The StartDate format is not a valid 'yyyy-MM-dd' format", fieldName);
                    errorObjects.add(errorObject);
                }
            }
        }
        return formattedDate;
    }

    private ResponseEntity<Object> getResponseSuccess(Study existingStudy) {

        ResponseSuccessStudyDTO responseSuccess = new ResponseSuccessStudyDTO();
        responseSuccess.setMessage("Existing Study");
        responseSuccess.setStudyOid(existingStudy.getOc_oid());
        responseSuccess.setUniqueStudyID(existingStudy.getUniqueIdentifier());
        responseSuccess.setSchemaName(existingStudy.getSchemaName());
        ResponseEntity<Object> response = new ResponseEntity(responseSuccess, HttpStatus.BAD_REQUEST);
        return response;
    }

    private ResponseEntity<Map<String, Object>> processSSOUserContext(HttpServletRequest request, String studyEnvUuid) throws Exception {
        ResponseEntity<Map<String, Object>> responseEntity = null;
        HttpSession session = request.getSession();
        if (session == null) {
            logger.error("Cannot proceed without a valid session.");
            return responseEntity;
        }
        Map<String, Object> userContextMap = (LinkedHashMap<String, Object>) session.getAttribute("userContextMap");
        if (userContextMap == null)
            return responseEntity;
        ResponseEntity<List<StudyEnvironmentRoleDTO>> studyUserRoles = studyBuildService.getUserRoles(request, false);
        Map<String, String> userMap = getUserInfo(request, userContextMap, studyUserRoles);
        UserAccountBean ub = (UserAccountBean) request.getSession().getAttribute("userBean");

        if ((ub == null || ub.getId() == 0) ||
                (userMap.get("username") != null &&
                        StringUtils.equals(ub.getName(), userMap.get("username")) != true)) {
            // we need to create the user
            try {
                responseEntity = userAccountController.createOrUpdateAccount(request, userMap);
                request.getSession().setAttribute("userBean", request.getAttribute("createdUaBean"));
            } catch (Exception e) {
                logger.error(e.getLocalizedMessage());
                throw e;
            }
        } else {
            HashMap<String, Object> userDTO = new HashMap<String, Object>();
            userDTO.put("username", ub.getName());
            userDTO.put("password", ub.getPasswd());
            userDTO.put("firstName", ub.getFirstName());
            userDTO.put("lastName", ub.getLastName());
            userDTO.put("apiKey", ub.getApiKey());
            responseEntity = new ResponseEntity<Map<String, Object>>(userDTO, org.springframework.http.HttpStatus.OK);
        }
        return responseEntity;
    }

    private Map<String, String> getUserInfo(HttpServletRequest request, Map<String, Object> userContextMap,
                                                ResponseEntity<List<StudyEnvironmentRoleDTO>> studyUserRoles) throws Exception {
        String studyEnvUuid = (String) request.getAttribute("studyEnvUuid");
        Map<String, String> map = new HashMap<>();
        ArrayList<LinkedHashMap<String, String>> roles = new ArrayList<>();

        for (StudyEnvironmentRoleDTO role : studyUserRoles.getBody()) {
            LinkedHashMap<String, String> studyRole = new LinkedHashMap<>();
            studyRole.put("roleName", role.getRoleName());
            studyRole.put("studyEnvUuid", role.getStudyEnvironmentUuid());
            roles.add(studyRole);
            if (role.getStudyEnvironmentUuid().equals(studyEnvUuid)) {
                map.put("role_name", role.getRoleName());
                UserAccountBean ub = (UserAccountBean) request.getSession().getAttribute("userBean");
                String userUuid = (String) userContextMap.get("userUuid");
                if ((ub == null || ub.getId() == 0)
                        || (StringUtils.isNotEmpty(userUuid) &&
                        StringUtils.equals(ub.getUserUuid(), userUuid) != true)) {
                    ResponseEntity<OCUserDTO> userInfo = studyBuildService.getUserDetails(request);
                    if (userInfo == null)
                        return null;
                    OCUserDTO userDTO = userInfo.getBody();
                    map.put("email", userDTO.getEmail());
                    if (StringUtils.isEmpty(userDTO.getOrganization()))
                        map.put("institution", "");
                    else
                        map.put("institution", userDTO.getOrganization());
                    map.put("fName", userDTO.getFirstName());
                    map.put("lName", userDTO.getLastName());
                    map.put("user_uuid", userDTO.getUuid());
                    map.put("username", userDTO.getUsername());
                } else {
                    map.put("email", ub.getEmail());
                    map.put("institution", ub.getInstitutionalAffiliation());
                    map.put("fName", ub.getFirstName());
                    map.put("lName", ub.getLastName());
                    map.put("user_uuid", ub.getUserUuid());
                }
            }
        }
        userContextMap.put("roles", roles);
        switch ((String) userContextMap.get("userType")) {
            case "Business Admin":
                map.put("user_type", UserType.SYSADMIN.getName());
                break;
            case "Tech Admin":
                map.put("user_type", UserType.TECHADMIN.getName());
                break;
            case "User":
                map.put("user_type", UserType.USER.getName());
                break;
            default:
                String error = "Invalid userType:" + (String) userContextMap.get("userType");
                logger.error(error);
                throw new Exception(error);
        }
        map.put("authorize_soap", "false");
        return map;
    }

    @RequestMapping(value = "/asyncStudyStatus", method = RequestMethod.GET)
    public ResponseEntity<Object> getAyncStudyStatus(HttpServletRequest request,
                                                     @RequestParam("uniqueId") String uniqueId) throws Exception {
        ResponseEntity<Object> response;

        AsyncStudyHelper asyncStudyHelper = AsyncStudyHelper.get(uniqueId);
        if (asyncStudyHelper != null) {
            response = new ResponseEntity<Object>(asyncStudyHelper, HttpStatus.OK);
        } else {
            // database lookup
            Study s = studyDao.findByColumnName(uniqueId, "uniqueIdentifier");
            HttpStatus httpStatus;
            if (s != null && StringUtils.isNotEmpty(s.getSchemaName())) {
                if (studyDao.doesStudyExist(uniqueId, s.getSchemaName())) {
                    asyncStudyHelper = new AsyncStudyHelper("Study Found", "ACTIVE");
                    httpStatus = HttpStatus.OK;
                } else {
                    asyncStudyHelper = new AsyncStudyHelper("Study Not Found", "ERROR");
                    httpStatus = HttpStatus.NOT_FOUND;
                }
            } else {
                asyncStudyHelper = new AsyncStudyHelper("Study Not Found", "ERROR");
                httpStatus = HttpStatus.NOT_FOUND;
            }
            response = new ResponseEntity<Object>(asyncStudyHelper, httpStatus);
        }

        return response;
    }

    private FacilityInfo processFacilityInfo(HashMap<String, Object> map) {
        FacilityInfo facilityInfo = new FacilityInfo();
        String facilityCity = (String) map.get("facilityCity");
        String facilityState = (String) map.get("facilityState");
        String facilityZip = (String) map.get("facilityZip");
        String facilityCountry = (String) map.get("facilityCountry");
        String facilityContact = (String) map.get("facilityContact");
        String facilityEmail = (String) map.get("facilityEmail");
        String facilityPhone = (String) map.get("facilityPhone");

        if (StringUtils.isNotEmpty(facilityCity))
            facilityInfo.setFacilityCity(facilityCity.trim());
        else
            facilityInfo.setFacilityCity("");
        if (StringUtils.isNotEmpty(facilityState))
            facilityInfo.setFacilityState(facilityState.trim());
        else
            facilityInfo.setFacilityState("");
        if (StringUtils.isNotEmpty(facilityZip))
            facilityInfo.setFacilityZip(facilityZip.trim());
        else
            facilityInfo.setFacilityZip("");
        if (StringUtils.isNotEmpty(facilityCountry))
            facilityInfo.setFacilityCountry(facilityCountry.trim());
        else
            facilityInfo.setFacilityCountry("");
        if (StringUtils.isNotEmpty(facilityContact))
            facilityInfo.setFacilityContact(facilityContact.trim());
        else
            facilityInfo.setFacilityContact("");
        if (StringUtils.isNotEmpty(facilityEmail))
            facilityInfo.setFacilityEmail(facilityEmail.trim());
        else
            facilityInfo.setFacilityEmail("");
        if (StringUtils.isNotEmpty(facilityPhone))
            facilityInfo.setFacilityPhone(facilityPhone.trim());
        else
            facilityInfo.setFacilityPhone("");

        return facilityInfo;
    }

    private class SiteParameters {
        String name;
        String principalInvestigator;
        String uniqueIdentifier;
        Integer expectedTotalEnrollment;
        String studyEnvSiteUuid;
        String ocOid;
        String statusStr;
        FacilityInfo facilityInfo;
        String studyVerificationDate;
        String startDate;
        HashMap<String, Object> map;
        Status status;
        Study parentStudy;
        String studyEnvUuid;
        UserAccountBean ownerUserAccount = null;
        Date formattedStartDate = null;
        Date formattedStudyDate = null;
        SiteSaveCheck siteSaveCheck;
        public SiteParameters(HashMap<String, Object> map, String studyEnvUuid) {
            this.map = map;
            this.studyEnvUuid = studyEnvUuid;
        }

        private void setParameters() {
            name = (String) map.get("briefTitle");
            principalInvestigator = (String) map.get("principalInvestigator");
            uniqueIdentifier = (String) map.get("uniqueIdentifier");
            expectedTotalEnrollment = (Integer) map.get("expectedTotalEnrollment");
            studyEnvSiteUuid = (String) map.get("studyEnvSiteUuid");
            ocOid = (String) map.get("ocOid");
            statusStr = (String) map.get("status");
            facilityInfo = processFacilityInfo(map);
            studyVerificationDate = (String) map.get("studyVerificationDate");
            startDate = (String) map.get("startDate");
        }

        ArrayList<ErrorObj> validateParameters(HttpServletRequest request) throws ParseException {
            ArrayList<ErrorObj> errorObjects = new ArrayList();


            if (uniqueIdentifier == null) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "uniqueIdentifier");
                errorObjects.add(errorObject);
            } else {
                uniqueIdentifier = uniqueIdentifier.trim();
            }
            if (name == null) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "BriefTitle");
                errorObjects.add(errorObject);
            } else {
                name = name.trim();
            }
            if (principalInvestigator == null) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "PrincipalInvestigator");
                errorObjects.add(errorObject);
            } else {
                principalInvestigator = principalInvestigator.trim();
            }

            if (expectedTotalEnrollment == null) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "ExpectedTotalEnrollment");
                errorObjects.add(errorObject);
            }

            if (studyEnvSiteUuid == null) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "studyEnvSiteUuid");
                errorObjects.add(errorObject);
            } else {
                studyEnvSiteUuid = studyEnvSiteUuid.trim();
            }

            if (ocOid == null) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "ocOid");
                errorObjects.add(errorObject);
            } else {
                ocOid = ocOid.trim();
            }
            if (StringUtils.isEmpty(statusStr)) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "status");
                errorObjects.add(errorObject);
            } else {
                statusStr = statusStr.toLowerCase();
            }
            statusStr = statusStr.equalsIgnoreCase("Design") ? "PENDING" : statusStr;
            status = Status.getByName(statusStr.toLowerCase());

            if (status == null) {
                ErrorObj errorObject = createErrorObject("Site Object", "Missing Field", "status");
                errorObjects.add(errorObject);
            }
            String format = "yyyy-MM-dd";
            SimpleDateFormat formatter = null;

            if (startDate != "" && startDate != null) {
                try {
                    formatter = new SimpleDateFormat(format);
                    formattedStartDate = formatter.parse(startDate);
                } catch (ParseException e) {
                    ErrorObj errorObject = createErrorObject("Site Object", "The StartDate format is not a valid 'yyyy-MM-dd' format", "StartDate");
                    errorObjects.add(errorObject);
                }
                if (formattedStartDate != null) {
                    if (!startDate.equals(formatter.format(formattedStartDate))) {
                        ErrorObj errorObject = createErrorObject("Site Object", "The StartDate format is not a valid 'yyyy-MM-dd' format", "StartDate");
                        errorObjects.add(errorObject);
                    }
                }
            }

            if (studyVerificationDate != "" && studyVerificationDate != null) {
                try {
                    formatter = new SimpleDateFormat(format);
                    formattedStudyDate = formatter.parse(studyVerificationDate);
                } catch (ParseException e) {
                    ErrorObj errorObject = createErrorObject("Site Object", "The Study Verification Date format is not a valid 'yyyy-MM-dd' format",
                            "StudyDateVerification");
                    errorObjects.add(errorObject);
                }
                if (formattedStudyDate != null) {
                    if (!studyVerificationDate.equals(formatter.format(formattedStudyDate))) {
                        ErrorObj errorObject = createErrorObject("Site Object", "The Study Verification Date format is not a valid 'yyyy-MM-dd' format",
                                "StudyDateVerification");
                        errorObjects.add(errorObject);
                    }
                }
            }
            request.setAttribute("uniqueSiteId", uniqueIdentifier);
            request.setAttribute("name", name);
            request.setAttribute("prinInvestigator", principalInvestigator);
            request.setAttribute("expectedTotalEnrollment", expectedTotalEnrollment);

            parentStudy = getStudyByEnvId(studyEnvUuid);
            if (parentStudy == null) {
                ErrorObj errorObject = createErrorObject("Study Object", "The Study Study Id provided in the URL is not a valid Study Id",
                        "Study Env Uuid");
                errorObjects.add(errorObject);
            } else if (parentStudy.isSite()) {
                ErrorObj errorObject = createErrorObject("Study Object", "The Study Study Id provided in the URL is not a valid Study Study Id",
                        "Study Env Uuid");
                errorObjects.add(errorObject);
            }

            if (parentStudy != null) {
                ownerUserAccount = getSiteOwnerAccount(request, parentStudy);
                if (ownerUserAccount == null) {
                    ErrorObj errorObject = createErrorObject("Site Object",
                            "The Owner User Account is not Valid Account or Does not have rights to Create Sites", "Owner Account");
                    errorObjects.add(errorObject);
                }
            }

            Validator v1 = new Validator(request);
            v1.addValidation("uniqueSiteId", Validator.NO_BLANKS);
            HashMap vError1 = v1.validate();
            if (!vError1.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Site Object", "This field cannot be blank.", "UniqueStudyId");
                errorObjects.add(errorObject);
            }
            Validator v2 = new Validator(request);
            v2.addValidation("name", Validator.NO_BLANKS);
            HashMap vError2 = v2.validate();
            if (!vError2.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Site Object", "This field cannot be blank.", "BriefTitle");
                errorObjects.add(errorObject);
            } else {
                // make sure no duplicate name sites are allowed for the same parent
                if (parentStudy != null) {

                        Study siteToVerify = studyDao.findByNameAndParent(name, parentStudy.getStudyId());
                        if (siteToVerify != null && siteToVerify.getStudyId() != 0) {
                            if (siteSaveCheck == SiteSaveCheck.CHECK_UNIQUE_SAVE) {
                                ErrorObj errorObject = createErrorObject("Site Object", "Duplicate site name during creation for the same parent study is not allowed.", "name");
                                errorObjects.add(errorObject);
                            } else if (siteSaveCheck == SiteSaveCheck.CHECK_UNIQUE_UPDATE) {
                                if (siteToVerify.getStudyEnvSiteUuid().equalsIgnoreCase(studyEnvSiteUuid) != true) {
                                    ErrorObj errorObject = createErrorObject("Site Object", "Updating site name to other existing site name for the same parent study is not allowed.", "name");
                                    errorObjects.add(errorObject);
                                }
                            }

                    }
                }
            }
            Validator v3 = new Validator(request);
            v3.addValidation("prinInvestigator", Validator.NO_BLANKS);
            HashMap vError3 = v3.validate();
            if (!vError3.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Site Object", "This field cannot be blank.", "PrincipleInvestigator");
                errorObjects.add(errorObject);
            }

            Validator v7 = new Validator(request);
            v7.addValidation("expectedTotalEnrollment", Validator.NO_BLANKS);
            HashMap vError7 = v7.validate();
            if (!vError7.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Site Object", "This field cannot be blank.", "ExpectedTotalEnrollment");
                errorObjects.add(errorObject);
            }

            if (request.getAttribute("name") != null && ((String) request.getAttribute("name")).length() > 255) {
                ErrorObj errorObject = createErrorObject("Site Object", "BriefTitle Length exceeds the max length 100", "BriefTitle");
                errorObjects.add(errorObject);
            }
            if (request.getAttribute("uniqueSiteId") != null && ((String) request.getAttribute("uniqueSiteId")).length() > 30) {
                ErrorObj errorObject = createErrorObject("Site Object", "UniqueStudyId Length exceeds the max length 30", "UniqueStudyId");
                errorObjects.add(errorObject);
            }
            if (request.getAttribute("prinInvestigator") != null && ((String) request.getAttribute("prinInvestigator")).length() > 255) {
                ErrorObj errorObject = createErrorObject("Site Object", "PrincipleInvestigator Length exceeds the max length 255", "PrincipleInvestigator");
                errorObjects.add(errorObject);
            }
            if ((request.getAttribute("expectedTotalEnrollment") != null)
                    && ((Integer) request.getAttribute("expectedTotalEnrollment") <= 0)) {
                ErrorObj errorObject = createErrorObject("Site Object", "ExpectedTotalEnrollment Length can't be negative or zero", "ExpectedTotalEnrollment");
                errorObjects.add(errorObject);
            }

            return errorObjects;
        }

    }

    /**
     * @api {post} /pages/auth/api/v1/studies/:studyEnvUuid/sites Create a site
     * @apiName createNewSite
     * @apiPermission Authenticate using api-key. admin
     * @apiVersion 3.8.0
     * @apiParam {String} studyEnvUuid Study environment uuid.
     * @apiParam {String} briefTitle Brief Title .
     * @apiParam {String} principalInvestigator Principal Investigator Name.
     * @apiParam {Integer} expectedTotalEnrollment Expected Total Enrollment number
     * @apiParam {String} secondaryStudyID Site Secondary Study Id  (Optional)
     * @apiParam {Date} startDate Start date
     * @apiParam {Date} studyDateVerification study Verification date
     * @apiParam {Array} assignUserRoles Assign Users to Roles for this Study.
     * @apiGroup Site
     * @apiHeader {String} api_key Users unique access-key.
     * @apiDescription Create a Site
     * @apiParamExample {json} Request-Example:
     * {
     * "briefTitle": "Site Study ID Name",
     * "principalInvestigator": "Principal Investigator Name",
     * "expectedTotalEnrollment": "10",
     * "assignUserRoles": [
     * { "username" : "userc", "role" : "Investigator"},
     * { "username" : "userb", "role" : "Clinical Research Coordinator"},
     * { "username" : "dm_normal", "role" : "Monitor"},
     * { "username" : "sd_root", "role" : "Data Entry Person"}
     * ],
     * "uniqueStudyID": "Site Study ID",
     * "startDate": "2011-11-11",
     * "secondaryStudyID" : "Secondary Study ID 1" ,
     * "studyDateVerification" : "2011-10-14"
     * }
     * @apiErrorExample {json} Error-Response:
     * HTTP/1.1 400 Bad Request
     * {
     * "message": "VALIDATION FAILED",
     * "studyDateVerification": "2011-10-14",
     * "principalInvestigator": "Principal Investigator Name",
     * "expectedTotalEnrollment": "10",
     * "errors": [
     * { "field": "studyEnvUuid", "resource": "Site Object","code": "Unique Study Id exist in the System" }
     * ],
     * "secondaryProId": "Secondary Study ID 1",
     * "siteOid": null,
     * "briefTitle": "Site Study ID Name",
     * "assignUserRoles": [
     * { "role": "Investigator", "username": "userc"},
     * { "role": "Clinical Research Coordinator", "username": "userb"},
     * { "role": "Monitor","username": "dm_normal"},
     * { "role": "Data Entry Person","username": "sd_root"}
     * ],
     * "studyEnvUuid": "Site Study ID",
     * "startDate": "2011-11-11"
     * }
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {
     * "message": "SUCCESS",
     * "siteOid": "S_SITEPROT",
     * "uniqueSiteStudyID": "Site Study IDqq"
     * }
     */

   /* {

        "uniqueIdentifier": "Site26 A",
            "ocOid" :"S_TONY(TEST)",
            "briefTitle": "Site26-A",
            "briefSummary": "Vauge summary of events.",
            "studyEnvSiteUuid": "07fe0825-8a42-4b4a-9ed3-91ac27e0a861",

            "principalInvestigator": "Dr. Dorian",
            "expectedTotalEnrollment": "100",
            "status":"available|frozen|pending|locked"
    }
*/
    @RequestMapping(value = "/{studyEnvUuid}/sites", method = RequestMethod.POST)
    public ResponseEntity<Object> createNewSites(HttpServletRequest request,
            @RequestBody HashMap<String, Object> map, @PathVariable("studyEnvUuid") String studyEnvUuid) throws Exception {
        logger.debug("Creating site(s) for study:" + studyEnvUuid);
        Study siteBean = null;
        ResponseEntity<Object> response = null;

        Locale locale = new Locale("en_US");
        request.getSession().setAttribute(LocaleResolver.getLocaleSessionAttributeName(), locale);
        ResourceBundleProvider.updateLocale(locale);
        SiteParameters siteParameters = new SiteParameters(map, studyEnvUuid);
        siteParameters.siteSaveCheck = SiteSaveCheck.CHECK_UNIQUE_SAVE;
        siteParameters.setParameters();
        ArrayList<ErrorObj> errorObjects = siteParameters.validateParameters(request);
        Study envSiteUuidStudy = studyDao.findByStudyEnvUuid(siteParameters.studyEnvSiteUuid);
        if (envSiteUuidStudy != null && envSiteUuidStudy.getStudyId() != 0) {
            ErrorObj errorObject = createErrorObject("Site Object", "studyEnvSiteUuid already exists", "studySiteEnvUuid");
            errorObjects.add(errorObject);
        }
        SiteDTO siteDTO = buildSiteDTO(siteParameters.uniqueIdentifier, siteParameters.name, siteParameters.principalInvestigator,
                siteParameters.expectedTotalEnrollment, siteParameters.status, siteParameters.facilityInfo);

        if (errorObjects != null && errorObjects.size() != 0) {
            response = new ResponseEntity(errorObjects, HttpStatus.BAD_REQUEST);
        } else {
            siteBean = buildSiteBean(siteParameters);
            siteBean.setSchemaName(siteParameters.parentStudy.getSchemaName());
            siteBean.setStudyEnvSiteUuid(siteParameters.studyEnvSiteUuid);
            siteBean.setEnvType(siteParameters.parentStudy.getEnvType());
            Study sBean = createStudy(siteBean);
            // get the schema study
            request.setAttribute("requestSchema", siteParameters.parentStudy.getSchemaName());
            Study schemaStudy = getStudyByEnvId(studyEnvUuid);
            siteBuildService.process(schemaStudy, sBean, siteParameters.ownerUserAccount,studyDao);
            siteDTO.setSiteOid(sBean.getOc_oid());
            siteDTO.setMessage(validation_passed_message);
            StudyUserRoleBean sub = null;
            ResponseSuccessSiteDTO responseSuccess = new ResponseSuccessSiteDTO();
            responseSuccess.setMessage(siteDTO.getMessage());
            responseSuccess.setSiteOid(siteDTO.getSiteOid());
            responseSuccess.setUniqueSiteStudyID(siteDTO.getUniqueSiteProtocolID());

            response = new ResponseEntity(responseSuccess, HttpStatus.OK);

        }
        return response;

    }

    @RequestMapping(value = "/{studyEnvUuid}/sites", method = RequestMethod.PUT)
    public ResponseEntity<Object> updateSiteSettings(HttpServletRequest request,
                                                     @RequestBody HashMap<String, Object> map, @PathVariable("studyEnvUuid") String studyEnvUuid) throws Exception {
        logger.debug("Updating site settings for study:" + studyEnvUuid);
        ResponseEntity<Object> response = null;

        Locale locale = new Locale("en_US");
        request.getSession().setAttribute(LocaleResolver.getLocaleSessionAttributeName(), locale);
        ResourceBundleProvider.updateLocale(locale);
        SiteParameters siteParameters = new SiteParameters(map, studyEnvUuid);
        siteParameters.siteSaveCheck = SiteSaveCheck.CHECK_UNIQUE_UPDATE;
        siteParameters.setParameters();
        ArrayList<ErrorObj> errorObjects = siteParameters.validateParameters(request);
        Study envSiteUuidStudy = studyDao.findByStudyEnvUuid(siteParameters.studyEnvSiteUuid);
        if (envSiteUuidStudy == null || envSiteUuidStudy.getStudyId() == 0) {
            ErrorObj errorObject = createErrorObject("Site Object", "studyEnvSiteUuid does not exist", "studySiteEnvUuid");
            errorObjects.add(errorObject);
        }
        SiteDTO siteDTO = buildSiteDTO(siteParameters.uniqueIdentifier, siteParameters.name, siteParameters.principalInvestigator,
                siteParameters.expectedTotalEnrollment, siteParameters.status, siteParameters.facilityInfo);
        siteDTO.setSiteOid(envSiteUuidStudy.getOc_oid());
        if (errorObjects != null && errorObjects.size() != 0) {
            for (ErrorObj errorObject : errorObjects) {
                logger.error(errorObject.toString());
            }
            response = new ResponseEntity(errorObjects, HttpStatus.BAD_REQUEST);
        } else {
            Study siteBean = studyDao.findByStudyEnvUuid(siteParameters.studyEnvSiteUuid);
            setChangeableSiteSettings(siteBean, siteParameters);
            studyDao.update(siteBean);

            // get the schema study
            request.setAttribute("requestSchema", siteBean.getSchemaName());
            Study schemaStudy = getStudyByEnvId(siteParameters.studyEnvSiteUuid);
            setChangeableSiteSettings(schemaStudy, siteParameters);
            ResponseSuccessSiteDTO responseSuccess = new ResponseSuccessSiteDTO();
            studyDao.update(schemaStudy);
            siteDTO.setMessage(validation_passed_message);
            responseSuccess.setMessage(siteDTO.getMessage());
            responseSuccess.setSiteOid(siteDTO.getSiteOid());
            responseSuccess.setUniqueSiteStudyID(siteDTO.getUniqueSiteProtocolID());
            response = new ResponseEntity(responseSuccess, HttpStatus.OK);

        }
        return response;

    }

    /**
     * @api {post} /pages/auth/api/v1/studies/:uniqueStudyId/eventdefinitions Create a study event
     * @apiName createEventDefinition
     * @apiPermission Authenticate using api-key. admin
     * @apiVersion 3.8.0
     * @apiParam {String} uniqueStudyId Study unique study ID.
     * @apiParam {String} name Event Name.
     * @apiParam {String} description Event Description.
     * @apiParam {String} category Category Name.
     * @apiParam {Boolean} repeating 'True' or 'False'.
     * @apiParam {String} type 'Scheduled' , 'UnScheduled' or 'Common'.
     * @apiGroup Study Event
     * @apiHeader {String} api_key Users unique access-key.
     * @apiDescription Creates a study event definition.
     * @apiParamExample {json} Request-Example:
     * {
     * "name": "Event Name",
     * "description": "Event Description",
     * "category": "Category Name",
     * "repeating": "true",
     * "type":"Scheduled"
     * }
     * @apiErrorExample {json} Error-Response:
     * HTTP/1.1 400 Bad Request
     * {
     * "name": "Event Name",
     * "message": "VALIDATION FAILED",
     * "type": "",
     * "errors": [
     * {"field": "Type","resource": "Event Definition Object","code": "Type Field should be Either 'Scheduled' , 'UnScheduled' or 'Common'"},
     * {"field": "Type","resource": "Event Definition Object","code": "This field cannot be blank."}
     * ],
     * "category": "Category Name",
     * "description": "Event Description",
     * "eventDefOid": null,
     * "repeating": "true"
     * }
     * @apiSuccessExample {json} Success-Response:
     * HTTP/1.1 200 OK
     * {
     * "message": "SUCCESS",
     * "name": "Event Name",
     * "eventDefOid": "SE_EVENTNAME"
     * }
     */
    @RequestMapping(value = "/{uniqueStudyID}/eventdefinitions", method = RequestMethod.POST)
    public ResponseEntity<Object> createEventDefinition(
            HttpServletRequest request, @RequestBody HashMap<String, Object> map, @PathVariable("uniqueStudyID") String uniqueStudyID) throws Exception {
        logger.debug("In Create Event Definition ");
        Study publicStudy = getStudyByUniqId(uniqueStudyID);
        request.setAttribute("requestSchema", publicStudy.getSchemaName());
        ArrayList<ErrorObj> errorObjects = new ArrayList();
        StudyEventDefinitionBean eventBean = null;
        ResponseEntity<Object> response = null;
        Locale locale = new Locale("en_US");
        request.getSession().setAttribute(LocaleResolver.getLocaleSessionAttributeName(), locale);
        ResourceBundleProvider.updateLocale(locale);

        String name = (String) map.get("name");
        String description = (String) map.get("description");
        String category = (String) map.get("category");
        String type = (String) map.get("type");
        String repeating = (String) map.get("repeating");

        EventDefinitionDTO eventDefinitionDTO = buildEventDefnDTO(name, description, category, repeating, type);

        if (name == null) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "Missing Field", "Name");
            errorObjects.add(errorObject);
        } else {
            name = name.trim();
        }
        if (description == null) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "Missing Field", "Description");
            errorObjects.add(errorObject);
        } else {
            description = description.trim();
        }
        if (category == null) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "Missing Field", "Category");
            errorObjects.add(errorObject);
        } else {
            category = category.trim();
        }
        if (type == null) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "Missing Field", "Type");
            errorObjects.add(errorObject);
        } else {
            type = type.trim();
        }
        if (repeating == null) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "Missing Field", "Repeating");
            errorObjects.add(errorObject);
        } else {
            repeating = repeating.trim();
        }
        if (repeating != null) {
            if (!repeating.equalsIgnoreCase("true") && !repeating.equalsIgnoreCase("false")) {
                ErrorObj errorObject = createErrorObject("Event Definition Object", "Repeating Field should be Either 'True' or 'False'", "Repeating");
                errorObjects.add(errorObject);
            }
        }

        if (type != null) {
            if (!type.equalsIgnoreCase("scheduled") && !type.equalsIgnoreCase("unscheduled") && !type.equalsIgnoreCase("common")) {
                ErrorObj errorObject = createErrorObject("Event Definition Object", "Type Field should be Either 'Scheduled' , 'UnScheduled' or 'Common'",
                        "Type");
                errorObjects.add(errorObject);
            }
        }

        request.setAttribute("name", name);
        request.setAttribute("description", description);
        request.setAttribute("category", category);
        request.setAttribute("type", type);
        request.setAttribute("repeating", repeating);

        Study parentStudy = getStudyByUniqId(uniqueStudyID);
        if (parentStudy == null) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "The Study Study Id provided in the URL is not a valid Study Id",
                    "Unique Study Study Id");
            errorObjects.add(errorObject);
        } else if (parentStudy.isSite()) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "The Study Study Id provided in the URL is not a valid Study Study Id",
                    "Unique Study Study Id");
            errorObjects.add(errorObject);
        }

        UserAccountBean ownerUserAccount = getStudyOwnerAccount(request);
        if (ownerUserAccount == null) {
            ErrorObj errorObject = createErrorObject("Study Object", "The Owner User Account is not Valid Account or Does not have Admin user type",
                    "Owner Account");
            errorObjects.add(errorObject);
        }

        Validator v1 = new Validator(request);
        v1.addValidation("name", Validator.NO_BLANKS);
        HashMap vError1 = v1.validate();
        if (!vError1.isEmpty()) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "This field cannot be blank.", "Name");
            errorObjects.add(errorObject);
        }

        if (name != null) {
            Validator v2 = new Validator(request);
            v2.addValidation("name", Validator.LENGTH_NUMERIC_COMPARISON, NumericComparisonOperator.LESS_THAN_OR_EQUAL_TO, 2000);
            HashMap vError2 = v2.validate();
            if (!vError2.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Event Definition Object", "The Length Should not exceed 2000.", "Name");
                errorObjects.add(errorObject);
            }
        }
        if (description != null) {
            Validator v3 = new Validator(request);
            v3.addValidation("description", Validator.LENGTH_NUMERIC_COMPARISON, NumericComparisonOperator.LESS_THAN_OR_EQUAL_TO, 2000);
            HashMap vError3 = v3.validate();
            if (!vError3.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Event Definition Object", "The Length Should not exceed 2000.", "Description");
                errorObjects.add(errorObject);
            }
        }
        if (category != null) {
            Validator v4 = new Validator(request);
            v4.addValidation("category", Validator.LENGTH_NUMERIC_COMPARISON, NumericComparisonOperator.LESS_THAN_OR_EQUAL_TO, 2000);
            HashMap vError4 = v4.validate();
            if (!vError4.isEmpty()) {
                ErrorObj errorObject = createErrorObject("Event Definition Object", "The Length Should not exceed 2000.", "Category");
                errorObjects.add(errorObject);
            }
        }
        Validator v5 = new Validator(request);
        v5.addValidation("repeating", Validator.NO_BLANKS);
        HashMap vError5 = v5.validate();
        if (!vError5.isEmpty()) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "This field cannot be blank.", "Repeating");
            errorObjects.add(errorObject);
        }

        Validator v6 = new Validator(request);
        v6.addValidation("type", Validator.NO_BLANKS);
        HashMap vError6 = v6.validate();
        if (!vError6.isEmpty()) {
            ErrorObj errorObject = createErrorObject("Event Definition Object", "This field cannot be blank.", "Type");
            errorObjects.add(errorObject);
        }


        if (errorObjects != null && errorObjects.size() != 0) {
            response = new ResponseEntity(errorObjects, org.springframework.http.HttpStatus.BAD_REQUEST);
        } else {
            eventBean = buildEventDefBean(name, description, category, type, repeating, ownerUserAccount, parentStudy);

            StudyEventDefinitionBean sedBean = createEventDefn(eventBean, ownerUserAccount);
            eventDefinitionDTO.setEventDefOid(sedBean.getOid());
            eventDefinitionDTO.setMessage(validation_passed_message);
        }
        ResponseSuccessEventDefDTO responseSuccess = new ResponseSuccessEventDefDTO();
        responseSuccess.setMessage(eventDefinitionDTO.getMessage());
        responseSuccess.setEventDefOid(eventDefinitionDTO.getEventDefOid());
        responseSuccess.setName(eventDefinitionDTO.getName());

        response = new ResponseEntity(responseSuccess, org.springframework.http.HttpStatus.OK);
        return response;

    }

    public Boolean verifyStudyTypeExist(String studyType) {
        ResourceBundle resadmin = core.org.akaza.openclinica.i18n.util.ResourceBundleProvider.getAdminBundle();
        if (!studyType.equalsIgnoreCase(resadmin.getString("interventional"))
                && !studyType.equalsIgnoreCase(resadmin.getString("observational"))
                && !studyType.equalsIgnoreCase(resadmin.getString("other"))) {
            logger.info("Study Type not supported");
            return false;
        }
        return true;
    }

    public StudyEventDefinitionBean buildEventDefBean(String name, String description, String category, String type, String repeating, UserAccountBean owner,
                                                      Study parentStudy) {

        StudyEventDefinitionBean sed = new StudyEventDefinitionBean();
        seddao = new StudyEventDefinitionDAO(dataSource, studyDao);
        ArrayList defs = seddao.findAllByStudy(parentStudy);
        if (defs == null || defs.isEmpty()) {
            sed.setOrdinal(1);
        } else {
            int lastCount = defs.size() - 1;
            StudyEventDefinitionBean last = (StudyEventDefinitionBean) defs.get(lastCount);
            sed.setOrdinal(last.getOrdinal() + 1);
        }

        sed.setName(name);
        sed.setCategory(category);
        sed.setType(type.toLowerCase());
        sed.setDescription(description);
        sed.setRepeating(Boolean.valueOf(repeating));
        sed.setStudyId(parentStudy.getStudyId());
        sed.setOwner(owner);
        sed.setStatus(core.org.akaza.openclinica.bean.core.Status.AVAILABLE);
        return sed;
    }

    public Study buildSiteBean(SiteParameters parameters) {
        Study study = new Study();
        ResourceBundle resadmin = core.org.akaza.openclinica.i18n.util.ResourceBundleProvider.getAdminBundle();
        study.setOc_oid(parameters.ocOid);
        study.setUniqueIdentifier(parameters.uniqueIdentifier);
        study.setStudy(parameters.parentStudy);
        study.setPublished(parameters.parentStudy.isPublished());
        study.setUserAccount(parameters.ownerUserAccount.toUserAccount(studyDao));
        setChangeableSiteSettings(study, parameters);
        return study;
    }

    public void setChangeableSiteSettings(Study study, SiteParameters parameters) {
        study.setName(parameters.name);
        study.setPrincipalInvestigator(parameters.principalInvestigator);
        study.setExpectedTotalEnrollment(parameters.expectedTotalEnrollment);
        study.setStatus(parameters.status);
        study.setDatePlannedStart(parameters.formattedStartDate);
        study.setProtocolDateVerification(parameters.formattedStudyDate);
        study.setFacilityCity(parameters.facilityInfo.getFacilityCity());
        study.setFacilityState(parameters.facilityInfo.getFacilityState());
        study.setFacilityZip(parameters.facilityInfo.getFacilityZip());
        study.setFacilityCountry(parameters.facilityInfo.getFacilityCountry());
        study.setFacilityContactName(parameters.facilityInfo.getFacilityContact());
        study.setFacilityContactPhone(parameters.facilityInfo.getFacilityPhone());
        study.setFacilityContactEmail(parameters.facilityInfo.getFacilityEmail());
        study.setUniqueIdentifier(parameters.uniqueIdentifier);
    }

    public Study createStudy(Study studyBean) {
        Study sBean = (Study) studyDao.create(studyBean);
        return sBean;
    }

    public StudyEventDefinitionBean createEventDefn(StudyEventDefinitionBean sedBean, UserAccountBean owner) {
        seddao = new StudyEventDefinitionDAO(dataSource);
        StudyEventDefinitionBean sdBean = (StudyEventDefinitionBean) seddao.create(sedBean);
        sdBean = (StudyEventDefinitionBean) seddao.findByPK(sdBean.getId());
        return sdBean;
    }

    public StudyUserRoleBean createRole(UserAccountBean ownerUserAccount, StudyUserRoleBean sub, DataSource dataSource) {
        udao = new UserAccountDAO(dataSource);
        StudyUserRoleBean studyUserRoleBean = (StudyUserRoleBean) udao.createStudyUserRole(ownerUserAccount, sub);
        return studyUserRoleBean;
    }

    public StudyUserRoleBean getUserRole(UserAccountBean ownerUserAccount, Study study) {
        udao = new UserAccountDAO(dataSource);
        StudyUserRoleBean surBean = udao.findRoleByUserNameAndStudyId(ownerUserAccount.getName(), study.getStudyId());
        return surBean;
    }

    public Study updateStudy(Study studyBean, UserAccountBean owner) {
        Study sBean = (Study) studyDao.update(studyBean);
        return sBean;
    }

    public void addValidationToDefinitionFields(Validator v) {

        v.addValidation("name", Validator.NO_BLANKS);
        v.addValidation("name", Validator.LENGTH_NUMERIC_COMPARISON, NumericComparisonOperator.LESS_THAN_OR_EQUAL_TO, 2000);
        v.addValidation("description", Validator.LENGTH_NUMERIC_COMPARISON, NumericComparisonOperator.LESS_THAN_OR_EQUAL_TO, 2000);
        v.addValidation("category", Validator.LENGTH_NUMERIC_COMPARISON, NumericComparisonOperator.LESS_THAN_OR_EQUAL_TO, 2000);

    }

    private UserAccountBean getUserAccount(String userName) {
        udao = new UserAccountDAO(dataSource);
        UserAccountBean userAccountBean = (UserAccountBean) udao.findByUserName(userName);
        return userAccountBean;
    }

    private Study getStudyByUniqId(String uniqueId) {
        Study studyBean = (Study) studyDao.findByUniqueId(uniqueId);
        return studyBean;
    }

    private Study getStudyByEnvId(String envUuid) {
        Study studyBean = (Study) studyDao.findByStudyEnvUuid(envUuid);
        return studyBean;
    }

    public Boolean roleValidForStatusChange(UserAccountBean userAccount, Study currentStudy, int interations){

        if (interations > 2)
            return false;

        if (logger.isDebugEnabled()) {
            logger.error("All Roles:" + userAccount.getRoles().toString());
            logger.error("Current study Id:" + currentStudy.getStudyId());
            for (StudyUserRoleBean userRoleBean : userAccount.getRoles()) {
                logger.error("***************inside StudyController study: " + userRoleBean.getStudyId() + " role: " + userRoleBean.getRoleName());
            }
        }

        long result = userAccount.getRoles()
                .stream()
                .filter(role -> currentStudy.getStudyId() == (role.getStudyId())
                        && (role.getRole().equals(Role.STUDYDIRECTOR)
                                || role.getRole().equals(Role.COORDINATOR)))
                .count();
        logger.info("Status returned from role check:" + (result > 0));

        if (result > 0)
            return true;

        return searchOtherEnvForRole(userAccount, currentStudy, interations);
    }


    private boolean searchOtherEnvForRole(UserAccountBean userAccount, Study currentStudy, int iterations) {
        boolean result = false;
        StudyEnvEnum altEnv;
        switch (currentStudy.getEnvType()) {
        case PROD:
            altEnv = StudyEnvEnum.TEST;
            break;
        case TEST:
            altEnv = StudyEnvEnum.PROD;
            break;
        default:
            altEnv = null;
            break;
        }
        String newOcId = currentStudy.getOc_oid().replaceFirst("\\(" + currentStudy.getEnvType() + "\\)", "(" + altEnv.toString() + ")");
        // get the new study with thus id
        Study altStudy = studyDao.findPublicStudy(newOcId);
        return roleValidForStatusChange(userAccount, altStudy, ++iterations);
    }

    public UserAccountBean getStudyOwnerAccount(HttpServletRequest request) {
        UserAccountBean ownerUserAccount = (UserAccountBean) request.getSession().getAttribute("userBean");
        if (!ownerUserAccount.isTechAdmin() && !ownerUserAccount.isSysAdmin()) {
            logger.info("The Owner User Account is not Valid Account or Does not have Admin user type");
            return null;
        }
        return ownerUserAccount;
    }

    public UserAccountBean getStudyOwnerAccountWithCreatedUser(HttpServletRequest request, ResponseEntity<Map<String, Object>> responseEntity) {
        UserAccountBean ownerUserAccount = null;
        if (responseEntity != null) {
            Map<String, Object> responseBody = responseEntity.getBody();
            if (responseBody != null && responseBody.get("username") != null) {
                String usernmae = (String) responseBody.get("username");
                UserAccountDAO userAccountDAO = new UserAccountDAO(dataSource);
                ownerUserAccount = (UserAccountBean) userAccountDAO.findByUserName(usernmae);
            }
        } else {
            ownerUserAccount = (UserAccountBean) request.getSession().getAttribute("userBean");
        }

        if (!ownerUserAccount.isTechAdmin() && !ownerUserAccount.isSysAdmin()) {
            logger.info("The Owner User Account is not Valid Account or Does not have Admin user type");
            return null;
        }
        return ownerUserAccount;
    }

    public UserAccountBean getSiteOwnerAccount(HttpServletRequest request, Study study) {
        UserAccountBean ownerUserAccount = (UserAccountBean) request.getSession().getAttribute("userBean");
        studyBuildService.updateStudyUserRoles(request, studyBuildService.getUserAccountObject(ownerUserAccount)
                , ownerUserAccount.getActiveStudyId(), null, false);
        StudyUserRoleBean currentRole = getUserRole(ownerUserAccount, study);

        if (currentRole.getRole().equals(Role.STUDYDIRECTOR) || currentRole.getRole().equals(Role.COORDINATOR)) {
            return ownerUserAccount;
        }

        logger.debug("Checking other study environments for a proper role");
        if (searchOtherEnvForRole(ownerUserAccount, study, 0))
            return ownerUserAccount;
        else
            return null;
    }

    public StudyDTO buildStudyDTO(String uniqueStudyID, String name, String briefSummary, String principalInvestigator, String sponsor,
                                  String expectedTotalEnrollment, String studyType, String status, String startDate, ArrayList<UserRole> userList) {
        if (status != null) {
            if (status.equals(""))
                status = "design";
        }

        StudyDTO studyDTO = new StudyDTO();
        studyDTO.setUniqueProtocolID(uniqueStudyID);
        studyDTO.setBriefTitle(name);
        studyDTO.setPrincipalInvestigator(principalInvestigator);
        studyDTO.setBriefSummary(briefSummary);
        studyDTO.setSponsor(sponsor);
        studyDTO.setProtocolType(studyType);
        studyDTO.setStatus(status);
        studyDTO.setExpectedTotalEnrollment(expectedTotalEnrollment);
        studyDTO.setStartDate(startDate);
        studyDTO.setAssignUserRoles(userList);
        return studyDTO;
    }

    public StudyDTO buildNewStudyDTO(String uniqueStudyID, String name) {
        StudyDTO studyDTO = new StudyDTO();
        studyDTO.setUniqueProtocolID(uniqueStudyID);
        studyDTO.setBriefTitle(name);
        studyDTO.setStatus("design");
        return studyDTO;
    }

    public SiteDTO buildSiteDTO(String uniqueSiteStudyID, String name, String principalInvestigator,
                                Integer expectedTotalEnrollment, Status status, FacilityInfo facilityInfo) {

        SiteDTO siteDTO = new SiteDTO();
        siteDTO.setUniqueSiteProtocolID(uniqueSiteStudyID);
        siteDTO.setBriefTitle(name);
        siteDTO.setPrincipalInvestigator(principalInvestigator);
        siteDTO.setExpectedTotalEnrollment(expectedTotalEnrollment);
        siteDTO.setStatus(status);
        siteDTO.setFacilityInfo(facilityInfo);
        return siteDTO;
    }

    public EventDefinitionDTO buildEventDefnDTO(String name, String description, String category, String repeating, String type) {
        EventDefinitionDTO eventDefinitionDTO = new EventDefinitionDTO();
        eventDefinitionDTO.setName(name);
        eventDefinitionDTO.setDescription(description);
        eventDefinitionDTO.setCategory(category);
        eventDefinitionDTO.setType(type);
        eventDefinitionDTO.setRepeating(repeating);

        return eventDefinitionDTO;
    }

    public Study buildStudyBean(String uniqueStudyId, String name, String briefSummary, String principalInvestigator, String sponsor,
                                    int expectedTotalEnrollment, String studyType, String status, Date startDate, UserAccountBean owner) {

        Study study = new Study();
        ResourceBundle resadmin = core.org.akaza.openclinica.i18n.util.ResourceBundleProvider.getAdminBundle();
        if (studyType.equals(resadmin.getString("interventional"))) {
            study.setProtocolType("interventional");
        } else if (studyType.equals(resadmin.getString("observational"))) {
            study.setProtocolType("observational");
        }
        ResourceBundle resword = core.org.akaza.openclinica.i18n.util.ResourceBundleProvider.getWordsBundle();
        if (resword.getString("available").equalsIgnoreCase(status))
            study.setStatus(Status.AVAILABLE);
        else if (resword.getString("design").equalsIgnoreCase(status) || status.equals(""))
            study.setStatus(Status.PENDING);

        study.setUniqueIdentifier(uniqueStudyId);
        study.setName(name);
        study.setPrincipalInvestigator(principalInvestigator);
        study.setSummary(briefSummary);
        study.setSponsor(sponsor);
        study.setExpectedTotalEnrollment(expectedTotalEnrollment);
        study.setDatePlannedStart(startDate);

        study.setUserAccount(owner.toUserAccount(studyDao));

        return study;
    }

    public Study buildNewStudyBean(String uniqueStudyId, String name, UserAccountBean accountBean) {

        Study study = new Study();
        ResourceBundle resadmin = core.org.akaza.openclinica.i18n.util.ResourceBundleProvider.getAdminBundle();

        ResourceBundle resword = core.org.akaza.openclinica.i18n.util.ResourceBundleProvider.getWordsBundle();
        study.setStatus(Status.PENDING);

        study.setUniqueIdentifier(uniqueStudyId);
        study.setName(name);
        study.setUserAccount(accountBean.toUserAccount(studyDao));
        return study;
    }

    public ErrorObj createErrorObject(String resource, String code, String field) {
        ErrorObj errorObject = new ErrorObj();
        errorObject.setCode(resource + " " + field);
        errorObject.setMessage(code);
        return errorObject;
    }

    public Role getStudyRole(String roleName, ResourceBundle resterm) {
        if (roleName.equalsIgnoreCase(resterm.getString("Study_Director").trim())) {
            return Role.STUDYDIRECTOR;
        } else if (roleName.equalsIgnoreCase(resterm.getString("Study_Coordinator").trim())) {
            return Role.COORDINATOR;
        } else if (roleName.equalsIgnoreCase(resterm.getString("Investigator").trim())) {
            return Role.INVESTIGATOR;
        } else if (roleName.equalsIgnoreCase(resterm.getString("Data_Entry_Person").trim())) {
            return Role.RESEARCHASSISTANT;
        } else if (roleName.equalsIgnoreCase(resterm.getString("Monitor").trim())) {
            return Role.MONITOR;
        } else
            return null;
    }

    public Role getSiteRole(String roleName, ResourceBundle resterm) {
        if (roleName.equalsIgnoreCase(resterm.getString("site_investigator").trim())) {
            return Role.INVESTIGATOR;
        } else if (roleName.equalsIgnoreCase(resterm.getString("site_Data_Entry_Person").trim())) {
            return Role.RESEARCHASSISTANT;
        } else if (roleName.equalsIgnoreCase(resterm.getString("site_monitor").trim())) {
            return Role.MONITOR;
        } else if (roleName.equalsIgnoreCase(resterm.getString("site_Data_Entry_Person2").trim())) {
            return Role.RESEARCHASSISTANT2;
        } else if (roleName.equalsIgnoreCase(resterm.getString("site_Data_Entry_Participant").trim())) {
            return Role.PARTICIPATE;
        } else
            return null;
    }


    private Status getStatus(String myStatus) {

        Status statusObj = null;

        if (myStatus != null) {
            statusObj = Status.getByName(myStatus.toLowerCase());
        }
        return statusObj;
    }

    public void verifyTemplateID(String templateID, ArrayList<ErrorObj> errorObjects) {


        Map<String, Object> data = ParticipantIdModel.getDataModel();

        StringWriter wtr = new StringWriter();
        Template template = null;
        if (templateID.length() > 255) {
            ErrorObj errorObject = createErrorObject("Study Object", "ID Template length must not exceed 255 characters.", "templateID");
            errorObjects.add(errorObject);
        }

        if (!templateID.contains(HELPER_RANDOM) && !templateID.contains(SITE_PARTICIPANT_COUNT)) {
            ErrorObj errorObject = createErrorObject("Study Object", "ID Template must include " + SITE_PARTICIPANT_COUNT + " or a " + RANDOM + " [helper.random(x)].", "templateID");
            errorObjects.add(errorObject);
        }


        if (errorObjects.size() == 0) {
            try {
                template = new Template("template name", new StringReader(templateID), freemarkerConfiguration);
                template.process(data, wtr);
                logger.info("Template ID Sample :" + wtr.toString());


            } catch (TemplateException te) {
                logger.error("Error while instantiating template for verify template id: ", te);
                ErrorObj errorObject = createErrorObject("Study Object", "Syntax of the ID Template is invalid.", "templateID");
                errorObjects.add(errorObject);

            } catch (IOException ioe) {
                logger.error("Error while processing template: ", ioe);
                ErrorObj errorObject = createErrorObject("Study Object", "Syntax of the ID Template is invalid.", "templateID");
                errorObjects.add(errorObject);

            }
        }
    }

    @RequestMapping( value = "/participantIdTemplate/model", method = RequestMethod.GET )
    public ResponseEntity<Object> getParticipantIdModel()

    {
        logger.info("In ParticipantId model controller");

        ParticipantIdModel participantIdModel = new ParticipantIdModel();

        ResponseEntity<Object> response = null;
        response = new ResponseEntity(participantIdModel, org.springframework.http.HttpStatus.OK);
        return response;
    }


    public void addParameterValue( String parameter , Study schemaStudy , String handle){
        boolean parameterValueExist = false;
        for (StudyParameterValue s : schemaStudy.getStudyParameterValues()) {
            if (s.getStudyParameter().getHandle().equals(handle)) {
                s.setValue(parameter);
                parameterValueExist = true;
                break;
            }
        }


        if (!parameterValueExist) {
            StudyParameterValue studyParameterValue = new StudyParameterValue();
            studyParameterValue = createStudyParameterValueWithHandleAndValue(handle, parameter);
            studyParameterValue.setStudy(schemaStudy);
            schemaStudy.getStudyParameterValues().add(studyParameterValue);
        }
    }
}


