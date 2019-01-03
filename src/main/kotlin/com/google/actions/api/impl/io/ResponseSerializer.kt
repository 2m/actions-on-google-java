/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.actions.api.impl.io

import com.google.actions.api.APP_DATA_CONTEXT
import com.google.actions.api.APP_DATA_CONTEXT_LIFESPAN
import com.google.actions.api.ActionContext
import com.google.actions.api.ActionResponse
import com.google.actions.api.impl.AogResponse
import com.google.actions.api.impl.DialogflowResponse
import com.google.api.services.actions_fulfillment.v2.model.RichResponse
import com.google.api.services.actions_fulfillment.v2.model.SimpleResponse
import com.google.api.services.dialogflow_fulfillment.v2.model.Context
import com.google.api.services.dialogflow_fulfillment.v2.model.WebhookResponse
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.set

internal class ResponseSerializer(
        private val sessionId: String? = "") {


    private companion object {
        val includeVersionMetadata = false
        val LOG = LoggerFactory.getLogger(ResponseSerializer::class.java.name)

        fun getLibraryMetadata(): Map<String, String> {
            val metadataProperties = ResourceBundle.getBundle("metadata")

            val map = HashMap<String, String>()
            map["name"] = "actions"
            map["language"] = "java"
            map["version"] = metadataProperties.getString("version")

            return map
        }
    }

    fun toJsonV2(response: ActionResponse): String {
        when (response) {
            is DialogflowResponse -> return serializeDialogflowResponseV2(
                    response)
            is AogResponse -> return serializeAogResponse(response)
        }
        LOG.warn("Unable to serialize the response.")
        throw Exception("Unable to serialize the response")
    }

    private fun serializeDialogflowResponseV2(
            dialogflowResponse: DialogflowResponse): String {
        val gson = GsonBuilder().create()
        val googlePayload = dialogflowResponse.googlePayload
        val webhookResponse = dialogflowResponse.webhookResponse
        val conversationData = dialogflowResponse.conversationData
        val contexts = dialogflowResponse.contexts

        if (googlePayload != null) {
            val aogPayload = DialogflowGooglePayload(googlePayload)

            val map = HashMap<String, Any>()
            map["google"] = aogPayload
            webhookResponse.payload = map
        }

        if (conversationData != null) {
            val context = ActionContext(APP_DATA_CONTEXT, APP_DATA_CONTEXT_LIFESPAN)
            val paramsMap = HashMap<String, Any>()
            paramsMap["data"] = gson.toJson(conversationData)
            context.parameters = paramsMap

            setContext(context, webhookResponse)
        }
        contexts?.forEach { setContext(it, webhookResponse) }

        val webhookResponseMap = webhookResponse.toMutableMap()
        if (includeVersionMetadata) {
            val metadata = HashMap<String, Any>()
            metadata["google_library"] = getLibraryMetadata()
            webhookResponseMap["metadata"] = metadata
        }
        return gson.toJson(webhookResponseMap)
    }

    private fun setContext(
            context: ActionContext,
            webhookResponse: WebhookResponse) {
        val ctx = webhookResponse.outputContexts?.find { it.name == context.name }
        if (ctx != null) {
            ctx.lifespanCount = context.lifespan
            ctx.parameters = context.parameters
        } else {
            if (webhookResponse.outputContexts == null) {
                webhookResponse.outputContexts = ArrayList<Context>()
            }
            val dfContext = Context()
            dfContext.name = getAsNamespaced(context.name)
            dfContext.lifespanCount = context.lifespan
            dfContext.parameters = context.parameters
            webhookResponse.outputContexts?.add(dfContext)
        }
    }

    private fun getAsNamespaced(name: String): String {
        val namespace = "$sessionId/contexts/"
        if (name.startsWith(namespace)) {
            return name
        }
        return namespace + name
    }

    private inner class DialogflowGooglePayload internal constructor(
            aogResponse: AogResponse) {
        internal var expectUserResponse: Boolean = aogResponse.expectUserResponse
        internal var richResponse: RichResponse? = null
        internal var noInputPrompts: Array<SimpleResponse>? = null
        internal var isSsml: Boolean = false
        internal var systemIntent: DFHelperIntent? = null
        internal var userStorage: String? = null

        init {
            if (aogResponse.appResponse != null) {
                val appResponse = aogResponse.appResponse
                if (expectUserResponse) {
                    richResponse = appResponse
                            ?.expectedInputs?.get(0)
                            ?.inputPrompt
                            ?.richInitialPrompt
                    val expectedIntent = appResponse
                            ?.expectedInputs?.get(0)
                            ?.possibleIntents?.get(0)
                    if (expectedIntent != null) {
                        systemIntent = DFHelperIntent()
                                .setIntent(expectedIntent.intent)
                                .setData(expectedIntent.inputValueData)
                    }
                } else {
                    richResponse = appResponse?.finalResponse?.richResponse
                }
            } else {
                richResponse = aogResponse.richResponse
                val helperIntents = aogResponse.helperIntents
                if (helperIntents != null && helperIntents.isNotEmpty()) {
                    val aogHelperIntent = helperIntents.get(0)

                    systemIntent = DFHelperIntent()
                            .setIntent(aogHelperIntent.intent)
                            .setData(aogHelperIntent.inputValueData)
                }
            }
            val userStorage = aogResponse.userStorage
            if (userStorage != null) {
                val dataMap = HashMap<String, Any?>()
                dataMap["data"] = userStorage
                this.userStorage = Gson().toJson(dataMap)
            }
            this.isSsml = false
        }
    }

    /**
     * This represents the "helperIntent" object as part of the Dialogflow's
     * payload.
     */
    private inner class DFHelperIntent {
        internal var intent: String = ""
        internal var data: Map<*, *> = HashMap<String, Any>()

        fun getIntent(): String {
            return intent
        }

        fun setIntent(intent: String): DFHelperIntent {
            this.intent = intent
            return this
        }

        fun getData(): Map<*, *> {
            return data
        }

        fun setData(data: Map<*, *>): DFHelperIntent {
            this.data = data
            return this
        }
    }

    private fun serializeAogResponse(aogResponse: AogResponse): String {
        aogResponse.prepareAppResponse()
        val appResponseMap = aogResponse.appResponse!!.toMutableMap()

        if (includeVersionMetadata) {
            val map = HashMap<String, Any>()
            map["GoogleLibraryInfo"] = getLibraryMetadata()
            appResponseMap["ResponseMetadata"] = map
        }

        return Gson().toJson(appResponseMap)
    }
}