package com.batsw.torExpertBundleController.service.impl;

import com.batsw.interProcessCommunication.IMessageListener;

import com.batsw.interProcessCommunication.*;
import com.batsw.torExpertBundleController.common.Constanst;
import com.batsw.torExpertBundleController.common.FileHandler;
import com.batsw.torExpertBundleController.common.ReturnValue;
import com.batsw.torExpertBundleController.common.StatusEnum;
import com.batsw.torExpertBundleController.model.ProcessModel;
import com.batsw.torExpertBundleController.model.TorBundleInformation;
import com.batsw.torExpertBundleController.model.TorProcessModel;
import com.batsw.torExpertBundleController.service.i.IConfigurationReader;
import com.batsw.torExpertBundleController.service.i.IParser;
import com.batsw.torExpertBundleController.service.i.IBundleProcess;
//import org.apache.log4j.Logger;
//import com.sun.xml.internal.ws.api.model.MEP;

import java.util.Scanner;


import java.io.IOException;

/**
 *  Helps manage the lifecycle of Tor bundle expert
 */
public class Bundle implements IBundleProcess, IMessageListener {

	public static Bundle instance = null;
	// Static and constants
	final private String NO_HOSTNAME = "No hostname";
	//public static final Logger log = Logger.getLogger(Bundle.class);

	// Defined Services
	private IConfigurationReader mConfugurationReader;
	private IParser<TorProcessModel> mTorchatcfgParser;
	private IParser<TorBundleInformation> mTorrcParser;
	private IIpcClient ipcServer;

	// Defined Models
	private TorBundleInformation mTorrcInformation;
	private TorProcessModel mTorchatcfgInformation;

	private ProcessModel bundleBuilderSettings;
	private String hostname = NO_HOSTNAME;

	private ProcessBuilder bundleProcessBuilder;
	private Process bundleHandle;


	public Process getProcessHandle() {
		return bundleHandle;
	}
	public TorBundleInformation getBundleInfo () {
		return mTorrcInformation;
	}

	public String getHostname() {
		return hostname;
	}

	public Bundle(IConfigurationReader configurationReader, IParser<TorProcessModel> torchatcfgParser,
		IParser<TorBundleInformation> torrcParser, IIpcClient ipc) {
		mConfugurationReader = configurationReader;
		mTorchatcfgParser = torchatcfgParser;
		mTorrcParser =torrcParser;
		bundleProcessBuilder = new ProcessBuilder();
		ipcServer = ipc;
	}

	public ReturnValue getConfiguration() {
		String torrcFileContent;
		String torchatcfgFileContent;
		ReturnValue result = new ReturnValue();
		String currentPath = System.getProperty("user.dir");
	//	log.info("Current directory : " + currentPath );

	//	log.info("Reading torchatcfg.json file");
		torchatcfgFileContent = mConfugurationReader.getConfigurationFile(currentPath, Constanst.TORCHATCFG_FILENAME);
		if ( torchatcfgFileContent != null) {
			mTorchatcfgInformation = mTorchatcfgParser.parse(torchatcfgFileContent);
		}
		if (torchatcfgFileContent == null || mTorchatcfgInformation == null) {
		//	log.warn("Invalid " + Constanst.TORCHATCFG_FILENAME + " loading default file");
			torchatcfgFileContent = mConfugurationReader.getDefaultConfigurationFile(Constanst.TORCHATCFG_FILENAME);
			FileHandler.writeToFile(currentPath + "\\" + Constanst.TORCHATCFG_FILENAME, torchatcfgFileContent);
			result.setSuccess(false);
		}
	//	log.info("Reading torchatcfg.json file");
		torrcFileContent = mConfugurationReader.getConfigurationFile(currentPath, Constanst.TORRC_FILENAME);
		if ( torrcFileContent != null) {
			mTorrcInformation = mTorrcParser.parse(torrcFileContent);
		}
		if (torrcFileContent == null || mTorrcInformation == null) {
		//	log.warn("Invalid " + Constanst.TORRC_FILENAME + " loading default file");
			torrcFileContent = mConfugurationReader.getDefaultConfigurationFile(Constanst.TORRC_FILENAME);
			FileHandler.writeToFile(currentPath + "\\" + Constanst.TORRC_FILENAME, torrcFileContent);
			result.setSuccess(false);
		}
		if (!result.getSuccess()){
			mTorchatcfgInformation = mTorchatcfgParser.parse(torchatcfgFileContent);
			mTorrcInformation = mTorrcParser.parse(torrcFileContent);
		}
		if (mTorchatcfgInformation == null || mTorrcInformation == null) {
			result.setSuccess(false);
		}
		return result;
	}

	public  ReturnValue run() {
		ReturnValue result = new ReturnValue();
		FilePathService fps = new FilePathService();
		mTorchatcfgInformation =  fps.getTorAbsolutePaths(mTorchatcfgInformation);


		// Create Bundle command
		bundleProcessBuilder.command(mTorchatcfgInformation.getCommandPath() + "\\" + mTorchatcfgInformation.getCommandName(),
				"-f",  mTorchatcfgInformation.getConfigurationFilePath() + "\\" + mTorchatcfgInformation.getConfigurationFilename());

		// Start Bundle expert
		try{
			HiddenServiceReader hiddenServiceReader = new HiddenServiceReader(mTorrcInformation.getHiddenServiceDir());
			ReturnValue hiddenServiceStatus = hiddenServiceReader.getHiddenServiceStatus();
			if (!hiddenServiceStatus.getSuccess()) {
		//		log.warn("New hostname will be assigned");
			}
			bundleHandle = bundleProcessBuilder.start();
			BundleLogMessageParser torProccessMessagesParser = new BundleLogMessageParser();
			Scanner torLogMessages = new Scanner(bundleHandle.getInputStream());
			while (torLogMessages.hasNextLine()) {
				String torData = torLogMessages.nextLine();
				ipcServer.sendMessage(new IpcMessage("bundle", torData));
				StatusEnum loadingStatus = torProccessMessagesParser.parse(torData);
				//log.info(torData);
				//log.info(loadingStatus.toString());

				if (loadingStatus.equals(StatusEnum.DONE)) {
				//	log.info("Bundle Started");
					hostname = hiddenServiceReader.getHostname();
					if ( hostname == NO_HOSTNAME || hostname == null) {
					//	log.error("No hostname found");
						result.setSuccess(false);
						stop();

					}
				//	log.info("hostname value: " + hostname);
					return result;

				}
				if (loadingStatus.equals(StatusEnum.ERROR)) {
					//log.info("Error");
					result.setSuccess(false);
					stop();
					return result;
				}
			}

		} catch (IOException e) {
		//	log.error("Unable to start bundle");
		}

		return result;
	}

	public ReturnValue stop() {
		ReturnValue returnValue = new ReturnValue();
		bundleHandle.destroy();
		System.out.println("Bundle Stopped");
		return returnValue;
	}

	public void SendMessage(IpcMessage message){

	}
}