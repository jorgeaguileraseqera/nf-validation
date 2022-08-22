package nextflow.validation

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import org.everit.json.schema.ValidationException
import org.everit.json.schema.loader.SchemaLoader
import org.everit.json.schema.Validator
import org.everit.json.schema.PrimitiveValidationStrategy
import org.json.JSONObject
import org.json.JSONTokener
import org.json.JSONArray
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.plugin.extension.Function
import nextflow.Session
import groovy.transform.CompileStatic
import java.util.regex.Matcher

@CompileStatic
class SchemaValidator extends PluginExtensionPoint {

    static final List<String> NF_OPTIONS = [
            // Options for base `nextflow` command
            'bg',
            'c',
            'C',
            'config',
            'd',
            'D',
            'dockerize',
            'h',
            'log',
            'q',
            'quiet',
            'syslog',
            'v',
            'version',

            // Options for `nextflow run` command
            'ansi',
            'ansi-log',
            'bg',
            'bucket-dir',
            'c',
            'cache',
            'config',
            'dsl2',
            'dump-channels',
            'dump-hashes',
            'E',
            'entry',
            'latest',
            'lib',
            'main-script',
            'N',
            'name',
            'offline',
            'params-file',
            'pi',
            'plugins',
            'poll-interval',
            'pool-size',
            'profile',
            'ps',
            'qs',
            'queue-size',
            'r',
            'resume',
            'revision',
            'stdin',
            'stub',
            'stub-run',
            'test',
            'w',
            'with-charliecloud',
            'with-conda',
            'with-dag',
            'with-docker',
            'with-mpi',
            'with-notification',
            'with-podman',
            'with-report',
            'with-singularity',
            'with-timeline',
            'with-tower',
            'with-trace',
            'with-weblog',
            'without-docker',
            'without-podman',
            'work-dir'
    ]

    private List<String> errors = []
    private List<String> warnings = []

    protected void init(Session session) {
    }

    boolean hasErrors() { errors.size()>0 }
    List<String> getErrors() { errors }

    boolean hasWarnings() { warnings.size()>0 }
    List<String> getWarnings() { warnings }

    //
    // Resolve Schema path relative to main workflow directory
    //
    static String getSchemaPath(baseDir, schema_filename='nextflow_schema.json') {
        return "${baseDir}/${schema_filename}"
    }

    /*
    * Function to loop over all parameters defined in schema and check
    * whether the given parameters adhere to the specifications
    */
    void validateParameters(Map params, String schema_filename='nextflow_schema.json') {

        // Clean the parameters
        def cleanedParams = cleanParameters(params)
        // Convert to JSONObject
        def paramsJSON = new JSONObject(new JsonBuilder(cleanedParams).toString())

        //=====================================================================//
        // Check for nextflow core params and unexpected params
        def slupper = new JsonSlurper()
        def Map parsed = (Map) slupper.parseText( schema_filename )
        def Map schemaParams = (Map) parsed.get('definitions')
        def specifiedParamKeys = params.keySet()

        // Collect expected parameters from the schema
        def expectedParams = []
        def enums = [:]
        for (group in schemaParams) {
            def Map properties = (Map) group.value['properties']
            for (p in properties) {
                def String key = (String) p.key
                expectedParams.push(key)
                def Map property = properties[key]
                if (property.containsKey('enum')) {
                    enums[key] = properties[key]['enum']
                }
            }
        }

        // Add known expected parameters from the pipeline
        expectedParams.push('fail_unrecognised_params')
        expectedParams.push('lenient_mode')

        for (String specifiedParam in specifiedParamKeys) {
            // nextflow params
            if (NF_OPTIONS.contains(specifiedParam)) {
                errors << "You used a core Nextflow option with two hyphens: '--${specifiedParam}'. Please resubmit with '-${specifiedParam}'".toString()
            }
            // unexpected params
            def String schema_ignore_params = params.schema_ignore_params
            def List params_ignore = schema_ignore_params ? schema_ignore_params.split(',') + 'schema_ignore_params' as List : []
            def expectedParamsLowerCase = expectedParams.collect{ it -> 
                def String p = it
                p.replace("-", "").toLowerCase() 
            }
            def specifiedParamLowerCase = specifiedParam.replace("-", "").toLowerCase()
            def isCamelCaseBug = (specifiedParam.contains("-") && !expectedParams.contains(specifiedParam) && expectedParamsLowerCase.contains(specifiedParamLowerCase))
            if (!expectedParams.contains(specifiedParam) && !params_ignore.contains(specifiedParam) && !isCamelCaseBug) {
                if (params.fail_unrecognised_params) {
                    errors << "* --${specifiedParam}: ${paramsJSON[specifiedParam]}".toString()
                } else {
                    warnings << "* --${specifiedParam}: ${paramsJSON[specifiedParam]}".toString()
                }
            }
        }

        //=====================================================================//
        // Validate parameters against the schema
        final rawSchema = new JSONObject(new JSONTokener(schema_filename))
        final schema = SchemaLoader.load(rawSchema)

        // Validate
        try {
            if (params.lenient_mode) {
                // Create new validator with LENIENT mode 
                Validator validator = Validator.builder()
                    .primitiveValidationStrategy(PrimitiveValidationStrategy.LENIENT)
                    .build();
                validator.performValidation(schema, paramsJSON);
            } else {
                schema.validate(paramsJSON)
            }
        }
        catch (ValidationException e) {
            JSONObject exceptionJSON = e.toJSON()
            collectErrors(exceptionJSON, paramsJSON, enums)
        }
    }

    //
    // Beautify parameters for --help
    //
    @Function
    String paramsHelp(String baseDir, Map params, String command, String schema_filename='nextflow_schema.json') {
        def Boolean monochrome_logs = params.monochrome_logs
        def colors = logColours(monochrome_logs)
        Integer num_hidden = 0
        String output  = ''
        output        += 'Typical pipeline command:\n\n'
        output        += "  ${colors.cyan}${command}${colors.reset}\n\n"
        Map params_map = paramsLoad(getSchemaPath(baseDir, schema_filename=schema_filename))
        Integer max_chars  = paramsMaxChars(params_map) + 1
        Integer desc_indent = max_chars + 14
        Integer dec_linewidth = 160 - desc_indent
        for (group in params_map.keySet()) {
            Integer num_params = 0
            String group_output = "$colors.underlined$colors.bold$group$colors.reset\n"
            def Map group_params = params_map.get(group)  // This gets the parameters of that particular group
            for (String param in group_params.keySet()) {
                def Map get_param = group_params.get(param)
                if (get_param.hidden && !params.show_hidden_params) {
                    num_hidden += 1
                    continue;
                }
                def String type = '[' + get_param.type + ']'
                def String description = get_param.description
                def defaultValue = get_param.default != null ? " [default: " + get_param.default.toString() + "]" : ''
                def description_default = description + colors.dim + defaultValue + colors.reset
                // Wrap long description texts
                // Loosely based on https://dzone.com/articles/groovy-plain-text-word-wrap
                if (description_default.length() > dec_linewidth){
                    List olines = []
                    String oline = "" // " " * indent
                    description_default.split(" ").each() { wrd ->
                        if ((oline.size() + wrd.size()) <= dec_linewidth) {
                            oline += wrd + " "
                        } else {
                            olines += oline
                            oline = wrd + " "
                        }
                    }
                    olines += oline
                    description_default = olines.join("\n" + " " * desc_indent)
                }
                group_output += "  --" +  param.padRight(max_chars) + colors.dim + type.padRight(10) + colors.reset + description_default + '\n'
                num_params += 1
            }
            group_output += '\n'
            if (num_params > 0){
                output += group_output
            }
        }
        if (num_hidden > 0){
            output += "$colors.dim !! Hiding $num_hidden params, use --show_hidden_params to show them !!\n $colors.reset"
        }
        output += "-${colors.dim}----------------------------------------------------${colors.reset}-"
        return output
    }

    //
    // Groovy Map summarising parameters/workflow options used by the pipeline
    //
    @Function
    LinkedHashMap paramsSummaryMap(Map workflow, String baseDir, Map params, String schema_filename='nextflow_schema.json') {
        // Get a selection of core Nextflow workflow options
        def Map workflow_summary = [:]
        if (workflow.revision) {
            workflow_summary['revision'] = workflow.revision
        }
        workflow_summary['runName']      = workflow.runName
        if (workflow.containerEngine) {
            workflow_summary['containerEngine'] = workflow.containerEngine
        }
        if (workflow.container) {
            workflow_summary['container'] = workflow.container
        }
        def String configFiles = workflow.configFiles
        workflow_summary['launchDir']    = workflow.launchDir
        workflow_summary['workDir']      = workflow.workDir
        workflow_summary['projectDir']   = workflow.projectDir
        workflow_summary['userName']     = workflow.userName
        workflow_summary['profile']      = workflow.profile
        workflow_summary['configFiles']  = configFiles.join(', ')

        // Get pipeline parameters defined in JSON Schema
        def Map params_summary = [:]
        def params_map = paramsLoad(getSchemaPath(baseDir, schema_filename=schema_filename))
        for (group in params_map.keySet()) {
            def sub_params = new LinkedHashMap()
            def Map group_params = params_map.get(group)  // This gets the parameters of that particular group
            for (param in group_params.keySet()) {
                if (params.containsKey(param)) {
                    def String params_value = params.get(param)
                    def Map group_params_value = group_params.get(param)
                    def String schema_value = group_params_value.default
                    def String param_type   = group_params_value.type
                    if (schema_value != null) {
                        if (param_type == 'string') {
                            if (schema_value.contains('$projectDir') || schema_value.contains('${projectDir}')) {
                                def sub_string = schema_value.replace('\$projectDir', '')
                                sub_string     = sub_string.replace('\${projectDir}', '')
                                if (params_value.contains(sub_string)) {
                                    schema_value = params_value
                                }
                            }
                            if (schema_value.contains('$params.outdir') || schema_value.contains('${params.outdir}')) {
                                def sub_string = schema_value.replace('\$params.outdir', '')
                                sub_string     = sub_string.replace('\${params.outdir}', '')
                                if ("${params.outdir}${sub_string}" == params_value) {
                                    schema_value = params_value
                                }
                            }
                        }
                    }

                    // We have a default in the schema, and this isn't it
                    if (schema_value != null && params_value != schema_value) {
                        sub_params.put(param, params_value)
                    }
                    // No default in the schema, and this isn't empty
                    else if (schema_value == null && params_value != "" && params_value != null && params_value != false) {
                        sub_params.put(param, params_value)
                    }
                }
            }
            params_summary.put(group, sub_params)
        }
        return [ 'Core Nextflow options' : workflow_summary ] << params_summary as LinkedHashMap
    }

    //
    // Beautify parameters for summary and return as string
    //
    @Function
    String paramsSummaryLog(Map workflow, String baseDir, Map params) {
        def Boolean monochrome_logs = params.monochrome_logs
        def colors = logColours(monochrome_logs)
        String output  = ''
        def params_map = paramsSummaryMap(workflow, baseDir, params)
        def max_chars  = paramsMaxChars(params_map)
        for (group in params_map.keySet()) {
            def Map group_params = params_map.get(group)  // This gets the parameters of that particular group
            if (group_params) {
                output += "$colors.bold$group$colors.reset\n"
                for (String param in group_params.keySet()) {
                    output += "  " + colors.blue + param.padRight(max_chars) + ": " + colors.green +  group_params.get(param) + colors.reset + '\n'
                }
                output += '\n'
            }
        }
        output += "!! Only displaying parameters that differ from the pipeline defaults !!\n"
        output += "-${colors.dim}----------------------------------------------------${colors.reset}-"
        return output
    }

    //
    // Loop over nested exceptions and print the causingException
    //
    private void collectErrors(JSONObject exJSON, JSONObject paramsJSON, Map enums, Integer limit=5) {
        def Iterable causingExceptions = (Iterable) exJSON['causingExceptions']
        if (causingExceptions.size() == 0) {
            def Matcher m = (Matcher) (exJSON['message'] =~ /required key \[([^\]]+)\] not found/)
            // Missing required param
            if(m.matches()){
                def List l = m[0] as ArrayList
                errors << "* Missing required parameter: --${l[1]}".toString()
            }
            // Other base-level error
            else if(exJSON['pointerToViolation'] == '#'){
                errors << "* ${exJSON['message']}".toString()
            }
            // Error with specific param
            else {
                def String param = (String) exJSON['pointerToViolation'] - ~/^#\//
                def param_val = paramsJSON[param].toString()
                if (enums.containsKey(param)) {
                    def error_msg = "* --${param}: '${param_val}' is not a valid choice (Available choices"
                    def String enums_param = enums[param]
                    if (enums_param.size() > limit) {
                        errors << "${error_msg} (${limit} of ${enums_param.size()}): ${enums_param[0..limit-1].join(', ')}, ... )".toString()
                    } else {
                        errors << "${error_msg}: ${enums_param.join(', ')})".toString()
                    }
                } else {
                    errors << "* --${param}: ${exJSON['message']} (${param_val})".toString()
                }
            }
        }
        for (ex in causingExceptions) {
            def JSONObject exception = (JSONObject) ex
            collectErrors(exception, paramsJSON, enums)
        }
    }

    //
    // Remove an element from a JSONArray
    //
    private static JSONArray removeElement(JSONArray json_array, String element) {
        def list = []
        int len = json_array.length()
        for (int i=0;i<len;i++){
            list.add(json_array.get(i).toString())
        }
        list.remove(element)
        JSONArray jsArray = new JSONArray(list)
        return jsArray
    }

    //
    // Remove ignored parameters
    //
    private static JSONObject removeIgnoredParams(Map raw_schema, Map params) {
        // Remove anything that's in params.schema_ignore_params
        def String schema_ignore_params = (String) params.schema_ignore_params
        schema_ignore_params.split(',').each{ ignore_param ->
            if(raw_schema.keySet().contains('definitions')){
                raw_schema.definitions.each { Map definition ->
                    for (key in definition.keySet()){
                        def Map definition_key = (Map) definition[key]
                        def Map definition_properties = (Map) definition_key.get("properties")
                        if (definition_properties.keySet().contains(ignore_param)){
                            // Remove the param to ignore
                            definition_properties.remove(ignore_param)
                            // If the param was required, change this
                            if (definition_key.containsKey("required")) {
                                def JSONArray required = (JSONArray) definition_key.required
                                def JSONArray cleaned_required = removeElement(required, ignore_param)
                                definition_key.put("required", cleaned_required)
                            }
                        }
                    }
                }
            }
            if(raw_schema.keySet().contains('properties')) {
                def Map raw_schema_properties = (Map) raw_schema.get("properties")
                if (raw_schema_properties.keySet().contains(ignore_param)) {
                    raw_schema_properties.remove(ignore_param)
                }
            } 
            if(raw_schema.keySet().contains('required')){
                def JSONArray raw_schema_required = (JSONArray) raw_schema.required
                if (raw_schema_required.contains(ignore_param)) {
                    def JSONArray cleaned_required = removeElement(raw_schema_required, ignore_param)
                    raw_schema.put("required", cleaned_required)
                }
            }
        }
        return raw_schema as JSONObject
    }

    //
    // Clean and check parameters relative to Nextflow native classes
    //
    private static Map cleanParameters(Map params) {
        def Map new_params = (Map) params.getClass().newInstance(params)
        for (p in params) {
            // remove anything evaluating to false
            if (!p['value']) {
                new_params.remove(p.key)
            }
            // Cast MemoryUnit to String
            if (p['value'] instanceof MemoryUnit) {
                new_params.replace(p.key, p['value'].toString())
            }
            // Cast Duration to String
            if (p['value'] instanceof Duration) {
                new_params.replace(p.key, p['value'].toString())
            }
            // Cast LinkedHashMap to String
            if (p['value'] instanceof LinkedHashMap) {
                new_params.replace(p.key, p['value'].toString())
            }
        }
        return new_params
    }

    //
    // This function tries to read a JSON params file
    //
    private static LinkedHashMap paramsLoad(String json_schema) {
        def params_map = new LinkedHashMap()
        try {
            params_map = paramsRead(json_schema)
        } catch (Exception e) {
            println "Could not read parameters settings from JSON. $e"
            params_map = new LinkedHashMap()
        }
        return params_map
    }

    //
    // Method to actually read in JSON file using Groovy.
    // Group (as Key), values are all parameters
    //    - Parameter1 as Key, Description as Value
    //    - Parameter2 as Key, Description as Value
    //    ....
    // Group
    //    -
    private static LinkedHashMap paramsRead(String json_schema) throws Exception {
        def json = new File(json_schema).text
        def Map schema = (Map) new JsonSlurper().parseText(json)
        def Map schema_definitions = (Map) schema.get('definitions')
        def Map schema_properties = (Map) schema.get('properties')
        /* Tree looks like this in nf-core schema
        * definitions <- this is what the first get('definitions') gets us
                group 1
                    title
                    description
                        properties
                        parameter 1
                            type
                            description
                        parameter 2
                            type
                            description
                group 2
                    title
                    description
                        properties
                        parameter 1
                            type
                            description
        * properties <- parameters can also be ungrouped, outside of definitions
                parameter 1
                    type
                    description
        */

        // Grouped params
        def params_map = new LinkedHashMap()
        schema_definitions.each { k, v ->
            def Map group = (Map) schema_definitions.k
            def Map group_property = (Map) group.properties // Gets the property object of the group
            def Map title = (Map) group.title
            def sub_params = new LinkedHashMap()
            group_property.each { innerkey, value ->
                sub_params.put(innerkey, value)
            }
            params_map.put(title, sub_params)
        }

        // Ungrouped params
        def ungrouped_params = new LinkedHashMap()
        schema_properties.each { innerkey, value ->
            ungrouped_params.put(innerkey, value)
        }
        params_map.put("Other parameters", ungrouped_params)

        return params_map
    }

    //
    // Get maximum number of characters across all parameter names
    //
    private static Integer paramsMaxChars( Map params_map) {
        Integer max_chars = 0
        for (group in params_map.keySet()) {
            def Map group_params = (Map) params_map.get(group)  // This gets the parameters of that particular group
            for (String param in group_params.keySet()) {
                if (param.size() > max_chars) {
                    max_chars = param.size()
                }
            }
        }
        return max_chars
    }

    //
    // ANSII Colours used for terminal logging
    //
    private static Map logColours(Boolean monochrome_logs) {
        Map colorcodes = [:]

        // Reset / Meta
        colorcodes['reset']      = monochrome_logs ? '' : "\033[0m"
        colorcodes['bold']       = monochrome_logs ? '' : "\033[1m"
        colorcodes['dim']        = monochrome_logs ? '' : "\033[2m"
        colorcodes['underlined'] = monochrome_logs ? '' : "\033[4m"
        colorcodes['blink']      = monochrome_logs ? '' : "\033[5m"
        colorcodes['reverse']    = monochrome_logs ? '' : "\033[7m"
        colorcodes['hidden']     = monochrome_logs ? '' : "\033[8m"

        // Regular Colors
        colorcodes['black']      = monochrome_logs ? '' : "\033[0;30m"
        colorcodes['red']        = monochrome_logs ? '' : "\033[0;31m"
        colorcodes['green']      = monochrome_logs ? '' : "\033[0;32m"
        colorcodes['yellow']     = monochrome_logs ? '' : "\033[0;33m"
        colorcodes['blue']       = monochrome_logs ? '' : "\033[0;34m"
        colorcodes['purple']     = monochrome_logs ? '' : "\033[0;35m"
        colorcodes['cyan']       = monochrome_logs ? '' : "\033[0;36m"
        colorcodes['white']      = monochrome_logs ? '' : "\033[0;37m"

        // Bold
        colorcodes['bblack']     = monochrome_logs ? '' : "\033[1;30m"
        colorcodes['bred']       = monochrome_logs ? '' : "\033[1;31m"
        colorcodes['bgreen']     = monochrome_logs ? '' : "\033[1;32m"
        colorcodes['byellow']    = monochrome_logs ? '' : "\033[1;33m"
        colorcodes['bblue']      = monochrome_logs ? '' : "\033[1;34m"
        colorcodes['bpurple']    = monochrome_logs ? '' : "\033[1;35m"
        colorcodes['bcyan']      = monochrome_logs ? '' : "\033[1;36m"
        colorcodes['bwhite']     = monochrome_logs ? '' : "\033[1;37m"

        // Underline
        colorcodes['ublack']     = monochrome_logs ? '' : "\033[4;30m"
        colorcodes['ured']       = monochrome_logs ? '' : "\033[4;31m"
        colorcodes['ugreen']     = monochrome_logs ? '' : "\033[4;32m"
        colorcodes['uyellow']    = monochrome_logs ? '' : "\033[4;33m"
        colorcodes['ublue']      = monochrome_logs ? '' : "\033[4;34m"
        colorcodes['upurple']    = monochrome_logs ? '' : "\033[4;35m"
        colorcodes['ucyan']      = monochrome_logs ? '' : "\033[4;36m"
        colorcodes['uwhite']     = monochrome_logs ? '' : "\033[4;37m"

        // High Intensity
        colorcodes['iblack']     = monochrome_logs ? '' : "\033[0;90m"
        colorcodes['ired']       = monochrome_logs ? '' : "\033[0;91m"
        colorcodes['igreen']     = monochrome_logs ? '' : "\033[0;92m"
        colorcodes['iyellow']    = monochrome_logs ? '' : "\033[0;93m"
        colorcodes['iblue']      = monochrome_logs ? '' : "\033[0;94m"
        colorcodes['ipurple']    = monochrome_logs ? '' : "\033[0;95m"
        colorcodes['icyan']      = monochrome_logs ? '' : "\033[0;96m"
        colorcodes['iwhite']     = monochrome_logs ? '' : "\033[0;97m"

        // Bold High Intensity
        colorcodes['biblack']    = monochrome_logs ? '' : "\033[1;90m"
        colorcodes['bired']      = monochrome_logs ? '' : "\033[1;91m"
        colorcodes['bigreen']    = monochrome_logs ? '' : "\033[1;92m"
        colorcodes['biyellow']   = monochrome_logs ? '' : "\033[1;93m"
        colorcodes['biblue']     = monochrome_logs ? '' : "\033[1;94m"
        colorcodes['bipurple']   = monochrome_logs ? '' : "\033[1;95m"
        colorcodes['bicyan']     = monochrome_logs ? '' : "\033[1;96m"
        colorcodes['biwhite']    = monochrome_logs ? '' : "\033[1;97m"

        return colorcodes
    }
}
